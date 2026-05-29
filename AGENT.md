# AGENT.md — Working Memory for Pekko LLM Agent Runtime

**Memory role:** this file is **working memory** for the current Pekko + LangChain4j + Ollama agent-runtime project.

Use this file to recover the live state of the project between sessions. Keep reusable build procedure, architectural patterns, best practices, and anti-patterns in `.agents/skills/pekko-llm-agent-runtime/SKILL.md`.

```text
.agents/skills/pekko-llm-agent-runtime/SKILL.md = procedural memory / reusable skill
AGENT.md                                           = working memory / current project state
```

---

## 1. Current Objective

Build a small local prototype of an LLM agent runtime using:

- Apache Pekko Typed for actor orchestration
- LangChain4j as the LLM harness
- Ollama as the default local model server
- vLLM as an OpenAI-compatible local model server option
- Java 21 and Gradle for the first implementation

Initial target behavior:

```text
User request
  -> Main bootstrap
  -> GatewayActor
  -> ResearchWorkflowActor or PlannerExecutorWorkflowActor
  -> LlmWorkerActor
  -> LangChain4j ChatModel
  -> local Ollama or vLLM model server
  -> final response
```

---

## 2. Current Implementation Stage

Status: **MVP actor runtime implemented with research and planner/executor workflows**.

Current milestone:

```text
Run one local request through a single-node Pekko actor system and receive one Ollama-backed LLM response.
```

Success criteria:

- `./gradlew run` starts one Pekko `ActorSystem`.
- The runtime submits one test request.
- The workflow actor sends one prompt to the LLM worker.
- The LLM worker calls Ollama through LangChain4j.
- The actor dispatcher is not blocked by the LLM call.
- The workflow returns or logs a final response.
- The actor system shuts down cleanly.

---

## 3. Active Architecture Decision

Use **single-node Pekko Typed first**.

Do not add cluster sharding, persistence, vector search, Kubernetes, tenant partitioning, or streaming until the simple local runtime works.

Current actor shape:

```text
RootActor / Main
  └── LlmWorkerActor
  └── ToolRegistryActor
        └── TimeToolActor
        └── WebSearchToolActor
        └── ArxivSearchToolActor
  └── GatewayActor
        └── ResearchWorkflowActor, created per request when AGENT_WORKFLOW=research
        └── PlannerExecutorWorkflowActor, created per request when AGENT_WORKFLOW=planner-executor
```

Current default workflow is `planner-executor`. Use `AGENT_WORKFLOW=research` to route through the one-shot research workflow.

Planner/executor actor shape:

```text
RootActor / Main
  └── LlmWorkerActor
  └── ToolRegistryActor
  └── GatewayActor
  └── PlannerExecutorWorkflowActor
        ├── planner step
        ├── configured tool steps via AGENT_TOOLS
        └── executor step
```

---

## 4. Runtime Requirements

Expected local requirements:

- Java 21+
- Gradle 8+
- Ollama installed locally or available through Docker
- A small local model pulled into Ollama

Default local Ollama base URL:

```text
http://localhost:11434
```

Default local vLLM OpenAI-compatible base URL:

```text
http://localhost:8000/v1
```

Current local default model:

```text
granite4:3b
```

Alternative local model candidates:

```text
llama3.1:8b
llama3.1:latest
gemma4:e4b
llama3.2:3b, if pulled locally
qwen2.5:3b
mistral
```

---

## 5. Dependency Decisions

Initial dependency direction:

```text
Pekko Typed
Pekko SLF4J
Pekko actor testkit typed
LangChain4j Ollama integration
Small Java HttpClient adapter for vLLM's OpenAI-compatible API
Logback runtime logging
JUnit 5
```

Current intended versions from the procedural skill:

```text
Java:        21
Scala bin:   2.13
Pekko:       1.6.0
LangChain4j: 1.15.0
```

Update this section if the actual implementation pins different versions.

---

## 6. Current Message Protocol Plan

Use immutable Java records for all messages crossing actor boundaries.

Minimum protocol concepts:

```text
AgentRequest(requestId, input)
AgentResponse(requestId, output)
LlmProtocol.Ask(requestId, prompt, replyTo)
LlmProtocol.Response(requestId, text, error)
```

Every LLM call should carry a correlation ID.

Recommended ID shape:

```text
<requestId>:research
<requestId>:plan
<requestId>:execute
```

---

## 7. Current Non-Blocking LLM Decision

LLM calls must not run directly on the actor dispatcher.

Current implemented pattern:

```text
Actor receives command
  -> submits blocking LangChain4j call to dedicated ExecutorService
  -> uses pipeToSelf or equivalent callback
  -> actor receives internal result message
  -> actor replies to requester
```

Initial executor candidate:

```java
Executors.newFixedThreadPool(4)
```

Current MVP uses a named fixed thread pool configured by `LLM_THREADS` and shuts it down after the actor system terminates. Replace this with a bounded executor before running serious load tests.

Timeout/failure policy for MVP:

```text
OllamaModelFactory sets the LangChain4j model timeout from LLM_TIMEOUT_SECONDS.
LlmWorkerActor returns failures as LlmProtocol.Response(error), not thrown exceptions.
ResearchWorkflowActor converts LLM results into AgentResponse and stops itself.
PlannerExecutorWorkflowActor calls the LLM twice using <requestId>:plan and <requestId>:execute, converts the final result into AgentResponse, and stops itself.
PlannerExecutorWorkflowActor invokes configured tools after planning and passes all tool results into the executor prompt. Configure with `AGENT_TOOLS`, for example `time.now,web.search,arxiv.search`.
Tool selection is policy-gated:

```text
Planner prompt asks for a tiny PLAN/TOOLS format.
Workflow parses the planner's TOOLS line.
If the tiny model omits tools, workflow applies a simple request heuristic.
Policy intersects requested tools with AGENT_TOOLS and caps with AGENT_MAX_TOOLS.
Only approved tools are invoked.
```
```

vLLM integration keeps the same actor/protocol boundary and swaps only the model factory/client layer. The vLLM client uses explicit JSON requests and forces HTTP/1.1 because the local vLLM server returned missing-body errors with Java requests that did not pin HTTP/1.1.

---

## 8. Current Project Structure Target

Preferred full structure after Gradle init:

```text
pekko-llm-agent-runtime/
├── AGENT.md
├── .agents/skills/pekko-llm-agent-runtime/SKILL.md
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── README.md
├── docker-compose.yml
└── app/
    ├── build.gradle.kts
    └── src/
        └── main/
        ├── java/
        │   └── com/example/agent/
        │       ├── Main.java
        │       ├── config/
        │       │   └── AppConfig.java
        │       ├── gateway/
        │       │   └── GatewayActor.java
        │       ├── llm/
        │       │   ├── ChatModelFactory.java
        │       │   ├── LlmBackend.java
        │       │   ├── LlmWorkerActor.java
        │       │   ├── LlmProtocol.java
        │       │   ├── OllamaModelFactory.java
        │       │   └── VllmModelFactory.java
        │       ├── workflow/
        │       │   ├── ResearchWorkflowActor.java
        │       │   └── PlannerExecutorWorkflowActor.java
        │       ├── protocol/
        │       │   ├── AgentRequest.java
        │       │   └── AgentResponse.java
        │       ├── tool/
        │       │   ├── ArxivSearchToolActor.java
        │       │   ├── TimeToolActor.java
        │       │   ├── ToolProtocol.java
        │       │   ├── ToolRegistryActor.java
        │       │   └── WebSearchToolActor.java
        │       └── prompts/
        │           └── PromptTemplates.java
        └── resources/
            └── application.conf
```

Current MVP implementation:

```text
app/src/main/java/com/example/agent/
├── Main.java
├── config/AppConfig.java
├── gateway/GatewayActor.java
├── llm/ChatModelFactory.java
├── llm/LlmBackend.java
├── llm/LlmWorkerActor.java
├── llm/LlmProtocol.java
├── llm/OllamaModelFactory.java
├── llm/VllmModelFactory.java
├── prompts/PromptTemplates.java
├── protocol/AgentRequest.java
├── protocol/AgentResponse.java
├── tool/TimeToolActor.java
├── tool/ToolProtocol.java
├── tool/ToolRegistryActor.java
├── tool/WebSearchToolActor.java
├── tool/ArxivSearchToolActor.java
├── workflow/ResearchWorkflowActor.java
└── workflow/PlannerExecutorWorkflowActor.java
```

---

## 9. Commands to Verify Locally

Start or verify Ollama:

```bash
ollama pull llama3.2:3b
ollama run llama3.2:3b
```

Verify Ollama API:

```bash
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2:3b",
  "prompt": "Say hello from Ollama",
  "stream": false
}'
```

Expected project command once implemented:

```bash
./gradlew run
```

Compile-only verification:

```bash
./gradlew compileJava
```

Workflow selection:

```bash
AGENT_WORKFLOW=planner-executor ./gradlew run
AGENT_WORKFLOW=research ./gradlew run
```

Backend and concurrency selection:

```bash
LLM_BACKEND=ollama AGENT_REQUESTS=2 ./gradlew run
LLM_BACKEND=vllm VLLM_BASE_URL=http://localhost:8000/v1 VLLM_MODEL=<served-model-name> VLLM_API_TYPE=completion AGENT_REQUESTS=8 ./gradlew run
```

vLLM concurrency sweep script:

```bash
VLLM_MODEL=<served-model-name> scripts/test-vllm-concurrency.sh
CONCURRENCY_LEVELS="1 4 8 16" AGENT_WORKFLOW=research VLLM_API_TYPE=completion scripts/test-vllm-concurrency.sh
```

For base/pretrained models such as `google/gemma-3-1b-pt`, use `VLLM_API_TYPE=completion`. Use `VLLM_API_TYPE=chat` only for chat/instruct models or when vLLM is started with a compatible chat template.

For instruct/chat models such as `google/gemma-3-1b-it`, prefer the full agent workflow:

```bash
VLLM_MODEL=google/gemma-3-1b-it \
VLLM_API_TYPE=chat \
VLLM_MAX_TOKENS=512 \
AGENT_WORKFLOW=planner-executor \
AGENT_TOOLS=time.now,web.search \
AGENT_MAX_TOOLS=2 \
CONCURRENCY_LEVELS="1 2 4 8" \
scripts/test-vllm-concurrency.sh
```

Use the exact model ID returned by:

```bash
curl http://localhost:8000/v1/models
```

---

## 10. Known Risks

Current risks to watch:

- Blocking the Pekko actor dispatcher with `model.chat(...)`.
- Creating too many workflow actors without lifecycle cleanup.
- Letting LLM output control actor spawning directly.
- Starting cluster sharding before the local actor model is proven.
- Using unbounded mailboxes, unbounded executors, or unbounded prompt/context growth.
- Logging sensitive prompt/user data by default.

---

## 11. Explicit Local DO NOTs

Do not do these in this project:

```text
DO NOT create one ActorSystem per request.
DO NOT create one ActorSystem per tenant for the MVP.
DO NOT call LangChain4j synchronously inside actor message handlers.
DO NOT add cluster sharding before the single-node flow works.
DO NOT add RAG/vector DB before the basic LLM worker works.
DO NOT stream every token as an actor message.
DO NOT store API keys, credentials, or private tokens in this file.
DO NOT copy the whole procedural skill into this file.
```

---

## 12. Next Actions

Recommended next implementation steps:

1. Create Gradle Java project.
2. Add Pekko Typed and LangChain4j Ollama dependencies.
3. Add minimal `application.conf`.
4. Implement `OllamaModelFactory`.
5. Implement `LlmProtocol`.
6. Implement `LlmWorkerActor` using a dedicated executor and `pipeToSelf`.
7. Implement `ResearchWorkflowActor`.
8. Implement `Main` to submit one test request.
9. Run against local Ollama.
10. Record actual versions, commands, and issues in this file.

---

## 13. Open Questions

Open questions to resolve during implementation:

- Resolved: first workflow was `ResearchWorkflowActor`; `PlannerExecutorWorkflowActor` now also exists and is the default route.
- Should the LLM worker be a single actor with executor-backed concurrency or a pool of LLM worker actors?
- Should request/response be CLI-only first, or should a minimal HTTP gateway be added after the actor flow works?
- Resolved: local Ollama models include `granite4:3b`, `gemma4:e4b`, `llama3.1:latest`, and `llama3.1:8b`.
- What timeout works reliably for the selected local model?
- What vLLM model name and server flags should be used for local concurrency tests?

---

## 14. Session Notes

Use this section for short, durable notes from implementation sessions. Keep it concise. Move reusable lessons into `pekko_llm_agent_runtime_skill.md` only if they become general procedure.

```text
2026-05-28: Created working-memory split. Skill file is procedural memory; AGENT.md is working memory. Implementation not yet confirmed.
2026-05-28: Gradle initialized as Kotlin DSL multi-project app under `app/`; wrapper generated. Local skill moved to `.agents/skills/pekko-llm-agent-runtime/SKILL.md`.
2026-05-28: Implemented MVP Java Pekko runtime: `Main` starts one ActorSystem, spawns one `LlmWorkerActor`, spawns one `ResearchWorkflowActor`, submits one request, prints response/failure, terminates actor system, then shuts down the LLM executor. `./gradlew compileJava` passes.
2026-05-28: `./gradlew run` confirmed the actor flow and clean shutdown. First run failed through the message path because `llama3.2:3b` was not pulled. `ollama list` showed `granite4:3b`, `gemma4:e4b`, `llama3.1:latest`, and `llama3.1:8b`; default changed to `granite4:3b`.
2026-05-28: `./gradlew run` succeeded against `granite4:3b`: the runtime printed a real LLM answer, then Pekko CoordinatedShutdown completed.
2026-05-28: Added `GatewayActor` and `PlannerExecutorWorkflowActor`. Default `AGENT_WORKFLOW` is now `planner-executor`; planner step uses `<requestId>:plan`, executor step uses `<requestId>:execute`. `./gradlew compileJava`, `./gradlew test`, and `./gradlew run` pass.
2026-05-29: Added `LLM_BACKEND=ollama|vllm`. Ollama uses `OllamaChatModel`; vLLM uses a local Java HttpClient adapter against `VLLM_BASE_URL` default `http://localhost:8000/v1` with `VLLM_API_KEY` default `EMPTY`. Added `AGENT_REQUESTS` to submit multiple requests through `GatewayActor` for concurrency testing. Verified `./gradlew compileJava`, `./gradlew test`, default Ollama run, and `AGENT_REQUESTS=2 AGENT_WORKFLOW=research ./gradlew run`.
2026-05-29: Added `scripts/test-vllm-concurrency.sh`. It checks `${VLLM_BASE_URL}/models`, then runs `./gradlew --quiet run` over `CONCURRENCY_LEVELS` with `LLM_BACKEND=vllm`. Syntax check and `./gradlew compileJava` pass.
2026-05-29: Reproduced vLLM missing-body error. Fixed Java vLLM client by sending explicit JSON and forcing HTTP/1.1. Local vLLM reports served model ID `google/gemma-3-1b-pt`; `gemma-3-1b-pt` is not accepted as the model name. Because this is a base/pretrained model without a chat template, use `VLLM_API_TYPE=completion` or serve a chat/instruct model for `VLLM_API_TYPE=chat`.
2026-05-29: Added vLLM `VLLM_SYSTEM_PROMPT` and `VLLM_MAX_TOKENS`; script now defaults to `AGENT_WORKFLOW=planner-executor` and `VLLM_API_TYPE=chat` for testing the full agentic path with instruct/chat models like `google/gemma-3-1b-it`. `bash -n scripts/test-vllm-concurrency.sh` and `./gradlew compileJava` pass.
2026-05-29: Added typed tool boundary: `ToolProtocol`, `ToolRegistryActor`, and deterministic `TimeToolActor` for `time.now`. `PlannerExecutorWorkflowActor` now runs planner LLM -> time tool -> executor LLM with tool context. `./gradlew compileJava` and `./gradlew test` pass. A runtime attempt with Ollama failed before tool invocation due to local CUDA OOM in Ollama, not actor/tool compilation.
2026-05-29: Added `web.search` and `arxiv.search` tools. `WebSearchToolActor` uses DuckDuckGo Instant Answer JSON over async Java HttpClient. `ArxivSearchToolActor` uses the arXiv API over async Java HttpClient and parses Atom XML. `AGENT_TOOLS` controls enabled tools; script defaults to `time.now,web.search,arxiv.search`. Verified `./gradlew compileJava`, `./gradlew test`, script syntax, and a single vLLM planner-executor run with all three tools enabled. The run returned successfully and logged `time.now`; web/arXiv results were included as tool context but not surfaced by the time-only prompt.
2026-05-29: Added tool-selection policy and runtime bounds. Planner prompt now asks for tiny `PLAN:` / `TOOLS:` output. Workflow parses requested tools, falls back to simple heuristics for tiny models, intersects with `AGENT_TOOLS`, and caps with `AGENT_MAX_TOOLS`. Added bounded LLM executor queue via `LLM_QUEUE_SIZE`, workflow timeout via `WORKFLOW_TIMEOUT_SECONDS`, and tool timeout via `TOOL_TIMEOUT_SECONDS`. `LlmWorkerActor` returns rejected executor submissions as response errors. Added request and summary `METRIC` output; vLLM script now prints `BENCH` lines from summary metrics. Verified `./gradlew compileJava`, `./gradlew test`, and script syntax. Live vLLM verification was blocked because `localhost:8000` refused connection.
2026-05-29: Search tools now normalize resource output with `Resources`, numbered titles, `URL`, and `Snippet` fields. `WebSearchToolActor` and `ArxivSearchToolActor` log resource counts and URLs instead of raw bodies. Executor prompt now asks for a short `Sources` section when tool context includes resource URLs. `./gradlew compileJava` and `./gradlew test` pass.
2026-05-29: Hardened search tool HTTP calls with explicit `User-Agent`/`Accept` headers and shorter normalized queries. Direct probe showed DuckDuckGo JSON no longer returns 403 with these headers, but may return empty results for niche queries. Direct arXiv probe returned HTTP 429, so arXiv should not be enabled by default in concurrency sweeps. Script default changed to `AGENT_TOOLS=time.now,web.search`; opt into arXiv explicitly for low-concurrency research runs.
2026-05-29: Made source handling deterministic. `PlannerExecutorWorkflowActor` now extracts exact `URL:` lines from successful tool outputs, logs resource counts and URLs at workflow level, and appends a `Sources` section with exact URLs if the executor model omits them. Executor prompt now forbids generic source labels like "web search results" and asks for exact URL fields only. Web search parser also reads DuckDuckGo `Results` in addition to abstract/related topics. `./gradlew compileJava` and `./gradlew test` pass.
```
