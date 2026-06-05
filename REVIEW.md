# Code Review: Pekko LLM Agent Runtime

## Executive Summary

The runtime has a **solid actor-model skeleton** — the GatewayActor, LlmWorkerActor, and ToolRegistryActor follow the right shape. However, the single most damaging issue is that `DefaultAgentSystemExecutorActor` has collapsed the entire orchestration into one God-actor with sequential, imperative, stateful flow. This violates the core design premise and is the root cause of the workflow, delegation, and tool-dispatch problems. Secondary issues span the API boundary, the tool dispatch model, the duplicated type hierarchy, and production-readiness gaps.

---

## Critical Issues

### 1. God-Actor: `DefaultAgentSystemExecutorActor` (Most Impactful)

**File:** `runtime/src/main/java/com/example/agent/runtime/agent/DefaultAgentSystemExecutorActor.java`

This actor holds 12+ mutable fields, drives a global sequential state machine (tools → RAG → delegates → synthesis), and makes every design decision inline. Issues:

- **Tool dispatch is pre-flight and blind.** `toolsFor()` collects *all* tools for all agents and fires them all upfront before any LLM sees the user input. Tools should be dispatched **after** the LLM decides a tool call is needed, not unconditionally. This is not tool-use — it is eager pre-fetching.
- **Delegation is not agent-to-agent.** All delegates receive the same prompt with the same input and cannot respond to each other's outputs. The gateway synthesizes last. This means "reviewer reviewing assistant" is structurally impossible — both receive the raw user input and the gateway concatenates their outputs.
- **Memory recall goes to `requestNextToolOrRag()`** (`onWrappedMemoryRecall`), which means a memory recall for a delegate re-enters the tool phase rather than re-entering the delegate phase. This is a state machine bug.
- **Single `pendingDelegateIndex` + `maxIterations` guard** at `requestNextDelegateOrSynthesis` compares iteration count against the gateway's task `maxIterations`, not the delegate's own task config. The gateway is gating sub-agent loops.
- **`observations` / `sources` lists are instance fields shared across all delegates** — tool observations from agent A pollute the context window given to agent B.

**Recommendation:** Replace with a per-agent `AgentStepActor` that owns one agent's full cycle (recall → LLM → decide-tool-or-done → invoke-tool → LLM again). The executor becomes a sequencer that creates child actors and fans in their `AgentStepResult`.

```
DefaultAgentSystemExecutorActor
  -> AgentStepActor("assistant")    // owns assistant's full ReAct loop
  -> AgentStepActor("reviewer")     // receives assistant's output as its input
  -> GatewaySynthesisStep           // receives all step outputs
```

---

### 2. Tool Dispatch Architecture is Broken

**Files:** `runtime/src/main/java/com/example/agent/runtime/tool/ToolRegistryActor.java`, `DefaultAgentSystemExecutorActor.java`

- `ToolRegistryActor.onInvokeTool` calls `definition.handler().invoke(request).whenComplete(...)` — this executes a blocking/async operation directly inside an actor message handler. The `whenComplete` callback captures `command` and calls `replyTo().tell()` from outside the actor. There is no dispatcher isolation — tool I/O shares the default dispatcher.
- `InvokeTool` carries a `List<AgentToolDefinition>` in every message (per-request tool definitions). This is expensive to copy and means per-request tool definitions bypass the registry's immutable index. The registry does a linear scan over `command.toolDefinitions()` first, then falls back to `definitions`. This dual-lookup creates ambiguous precedence.
- `AgentToolHandler` is a `@FunctionalInterface` — tool logic runs wherever `invoke()` is called without any executor boundary. There is no bounded dispatcher for tool I/O.

**Recommendation:**
1. Remove per-request `toolDefinitions` from `InvokeTool`. All tools should be registered at runtime startup. Per-workflow tool overrides belong in the registry, not in the message.
2. Wrap `definition.handler().invoke(request)` in `CompletableFuture.supplyAsync(..., toolExecutor)` with a bounded dispatcher, the same way `LlmWorkerActor` does it.
3. Use `pipeToSelf` after the future, not `whenComplete`, to return results through the actor mailbox.

---

### 3. Duplicated Type Hierarchy (API vs Runtime Internal)

The project maintains two parallel type families:

| API (public) | Runtime internal |
|---|---|
| `AgentSystem` | `AgentSystemDefinition` |
| `Agent` | `AgentDefinition` |
| `GatewayAgent` | `GatewayAgentDefinition` |
| `AgentMemoryConfig` | `MemoryDefinition` |
| `AgentTaskDefinition` | `TaskDefinition` |

`AgentSystemMapper` translates between them at every `run()` call. `TaskDefinition` just wraps `AgentTaskDefinition` and passes it through. `AgentDefinition` is structurally identical to `Agent`. This is ~200 lines of redundant code with no semantic difference. The only value would be if the internal types needed actor-specific fields — they don't.

**Recommendation:** Delete the internal definition types and the mapper. Use the API records directly in `DefaultAgentSystemExecutorActor` and `GatewayActor`. The `api` package is already part of the runtime JAR.

---

### 4. `AssistantWorkflow` — Reviewer Agent Is Commented Out

**File:** `client/src/main/java/com/example/agent/AssistantWorkflow.java`

```java
this.system = AgentSystem.builder()
        .entrypoint(gateway)
        .agent(assistant)
        // .agent(reviewer)  ← never registered
        .build();
```

The `reviewer` agent is built but never added to the system. The gateway still lists it as a delegate. This means `DefaultAgentSystemExecutorActor.delegatesFor()` will find `reviewer` in `entrypoint().delegates()` but not in `system.agents()`, so it silently drops it. No warning is emitted. The `ANSWER_RESPONSE` task definition is dead code.

This exposes a design gap: there is no validation that all declared delegates exist in the `AgentSystem`.

---

### 5. `ToolRegistryActor` Executes Tool I/O on the Actor Thread

**File:** `runtime/src/main/java/com/example/agent/runtime/tool/ToolRegistryActor.java`

```java
definition.handler().invoke(request).whenComplete((result, failure) -> {
    command.replyTo().tell(new ToolProtocol.ToolResult(...));
});
```

The `handler().invoke()` call is made synchronously on the actor thread. If the handler blocks inside `invoke()` before returning the future, or if `whenComplete` blocks, it stalls the actor. There is no dispatcher boundary at all.

---

## Significant Issues

### 6. `GatewayActor` is a Passthrough with No Policy

**File:** `runtime/src/main/java/com/example/agent/gateway/GatewayActor.java`

The gateway exists solely to spawn `DefaultAgentSystemExecutorActor` children. It does no rate limiting, tenant isolation, circuit breaking, or back-pressure. For a production library, the gateway is the right place to:
- Track in-flight request counts per tenant
- Reject requests when at capacity (`maxConcurrentRequests`)
- Log security/audit events before any model interaction

Currently a client can trivially spawn unbounded executor actors.

---

### 7. `WorkflowEntityActor` is Completely Disconnected

**File:** `runtime/src/main/java/com/example/agent/runtime/checkpoint/WorkflowEntityActor.java`

This is a well-structured Pekko Persistence event-sourced actor, but no code path in `DefaultAgentSystemExecutorActor`, `GatewayActor`, or `ActorAgentRuntimeService` ever creates or talks to it. The entire checkpoint/recovery subsystem (`AgentCheckpoint`, `ContextManifest`, `CheckpointRecoveryPlan`, `WorkflowSharding`, `TaskExecutionRecord`) is dead code. This is significant bloat — ~8 files, hundreds of lines — carrying Cassandra and cluster-sharding dependencies (`pekko-cluster-sharding-typed`, `pekko-persistence-cassandra`) that inflate the JAR and require infrastructure even when unused.

---

### 8. `AgentRuntimeClient` Wraps `AgentRuntime` Which Starts a Full `ActorSystem`

**File:** `runtime/src/main/java/com/example/agent/api/AgentRuntimeClient.java`

`AgentRuntimeClient.Builder.build()` creates a new `AgentRuntime` which starts a full `ActorSystem`. The client's `Builder` also accepts `tools` but `AgentRuntime.Builder` is the real configurator — the tool list in `AgentRuntimeClient.Builder` just passes through. This layered builder delegation is confusing and provides no isolation.

In `Main.java`, the `runHttp` path builds `AgentRuntime` directly and `runCli` builds via `AgentRuntimeClient` — two different code paths to the same runtime, one without HTTP.

---

### 9. Prompt Assembly is String-Templating Only — No Tool-Call Parsing Loop

**File:** `runtime/src/main/java/com/example/agent/runtime/agent/DefaultAgentSystemExecutorActor.java`

`delegatePrompt()` and `gatewayPrompt()` produce raw `String` values. The LLM has no structured way to express a tool call. There is no tool-call parsing anywhere: the LLM response is accepted as final text output. This means the "tool observations" in the context window are proactively fetched results, not LLM-driven selections.

For a ReAct-style runtime, the executor needs to detect whether the LLM response contains a tool invocation and loop. Currently there is no loop — each delegate gets exactly one LLM call regardless of `maxIterations`.

---

### 10. `AgentTaskDefinition.maxIterations` Has No Effect

**File:** `runtime/src/main/java/com/example/agent/runtime/agent/DefaultAgentSystemExecutorActor.java`

```java
int maxIterations = system.entrypoint().acceptedTask() == null
        ? 4
        : system.entrypoint().acceptedTask().resolvedMaxIterations();
if (pendingDelegateIndex >= delegates.size() || pendingDelegateIndex >= maxIterations) {
```

`maxIterations` caps the **number of delegates** dispatched, not the iterations within one agent's reasoning loop. The workflow client sets `maxIterations(2)` on task definitions expecting it to control the agent's thought steps. It does not.

---

## Moderate Issues

### 11. `ToolProtocol.InvokeTool` Carries `List<AgentToolDefinition>` in Every Message

Tool handler lambdas (closures over `HttpClient`, etc.) are included in every `InvokeTool` message. These are not serializable and will break if cluster remoting is ever enabled. Per-request tool definitions should not be in messages.

---

### 12. `GatewayAgent` Default `acceptedTask` is `"java.lang.String"`

**File:** `runtime/src/main/java/com/example/agent/api/GatewayAgent.java`

```java
private AgentTaskDefinition acceptedTask = AgentTaskDefinition.named("java.lang.String").build();
```

`"java.lang.String"` is not a task type name — it is a leftover placeholder default. `AgentRuntime.validateTask()` checks whether the submitted task name matches through `system.taskDefinition(task.name())`. Since the gateway never registers `ANSWER_QUESTION` as its accepted task, task routing is silently broken. The gateway and the workflow task definition are not wired.

---

### 13. Memory Recall Routing Bug

**File:** `runtime/src/main/java/com/example/agent/runtime/agent/DefaultAgentSystemExecutorActor.java`

```java
private Behavior<Command> onWrappedMemoryRecall(WrappedMemoryRecall wrapped) {
    recalledMemory.put(wrapped.agentName(), wrapped.recalled().events());
    return requestNextToolOrRag();  // ← wrong: returns to tool phase
}
```

After recalling memory for a delegate, the actor returns to `requestNextToolOrRag()` (the start of the pipeline). It should proceed to dispatch the pending delegate LLM call, not restart from tools.

---

### 14. `SampleTools` Belongs in `example/`, Not `client/`

**File:** `client/src/main/java/com/example/agent/tools/SampleTools.java`

The Arxiv XML parser in `formatArxiv()` correctly disables external DTD loading and entity expansion. However, `SampleTools` should not be in `client/` if the runtime is published as a library — users would pull in DuckDuckGo and Arxiv integration as transitive compile dependencies. It should live in `example/` or a dedicated `runtime-samples` module.

---

### 15. Missing `AgentSystem` Validation: Declared Delegates Must Exist

No check validates that every name in `gateway.delegates()` corresponds to an `Agent` in `AgentSystem.agents`. Silent drops (as in issue 4) will confuse users. This validation should fail fast at `AgentSystem.build()` time.

---

## Production Readiness Gaps

| Area | Issue |
|---|---|
| **Dependency bloat** | `pekko-cluster-sharding-typed`, `pekko-persistence-cassandra`, ONNX Runtime, Apache Tika (full parser package), DJL tokenizers are all in the main compile scope. If checkpointing is not wired, those first two should be `optional` or moved to a sub-module. ONNX/Tika/DJL only serve the embedding path. |
| **No back-pressure on GatewayActor** | Unbounded actor spawning per request. |
| **Blocking shutdown** | `AgentRuntime.close()` calls `join()` on the actor system termination future — a blocking call inside what could be a shutdown hook thread. Use `whenComplete` with a latch. |
| **vLLM gRPC path** | `VllmGrpcChatModel` exists but is not wired into `ChatModelFactory` through a clean selection path. |
| **Tool timeout race** | The `ToolTimeout` message races with `WrappedToolResult`. If the result arrives just after the timeout fires and increments `pendingToolIndex`, the real result will be processed for the wrong tool slot. |
| **Package namespace** | `com.example` must be changed before publishing as a library. |
| **`AgentRuntime.Builder.build()` thread safety** | The build is not guarded against concurrent invocation. |

---

## Summary: Prioritized Action Plan

### P0 — Fix before anything else

1. **Fix `onWrappedMemoryRecall` routing bug** — trivial one-line fix, causes silent wrong behavior in every multi-agent workflow.
2. **Add `AgentSystem` delegate existence validation** — prevents the silent reviewer-drop at `AgentSystem.build()`.
3. **Fix `GatewayAgent` default `acceptedTask`** — `"java.lang.String"` default breaks task routing; default should require explicit task assignment or use the gateway name.

### P1 — Architecture corrections

4. **Refactor `DefaultAgentSystemExecutorActor`** into a sequencer + per-agent `AgentStepActor` child. Each step actor owns one agent's recall→LLM→tool-decision→tool-call→LLM loop.
5. **Remove per-request `toolDefinitions` from `InvokeTool`**. Register all tools at `AgentRuntime` startup, not per-request.
6. **Add dispatcher boundary in `ToolRegistryActor`** — wrap `handler().invoke()` in a bounded executor and use `pipeToSelf`.
7. **Add in-flight request cap to `GatewayActor`**.

### P2 — Cleanup and production prep

8. **Delete the internal definition types** (`AgentSystemDefinition`, `AgentDefinition`, `GatewayAgentDefinition`, `MemoryDefinition`, `TaskDefinition`) and `AgentSystemMapper`. Use the API records directly.
9. **Move checkpoint subsystem to an optional module** or behind a feature flag. Remove `pekko-cluster-sharding-typed` and `pekko-persistence-cassandra` from the default compile scope.
10. **Move `SampleTools` to `example/`**.
11. **Rename `com.example`** package to a real namespace.
12. **`maxIterations` should control per-agent reasoning loop iterations**, not delegate count.
