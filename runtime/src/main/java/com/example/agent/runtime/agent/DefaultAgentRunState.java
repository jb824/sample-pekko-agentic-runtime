package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentSystem;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.runtime.memory.AgentMemoryEvent;
import org.apache.pekko.actor.typed.ActorRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

record DefaultAgentRunState(
        AgentRequest request,
        AgentSystem system,
        ActorRef<AgentResult> replyTo,
        long startedAtMs,
        List<Agent> delegates,
        int delegateIndex,
        String ragContext,
        String lastStepOutput,
        Map<String, String> delegateOutputs,
        List<String> allSources,
        List<AgentMemoryEvent> gatewayMemory
) {
    DefaultAgentRunState {
        request = Objects.requireNonNull(request);
        system = Objects.requireNonNull(system);
        replyTo = Objects.requireNonNull(replyTo);
        startedAtMs = Math.max(0L, startedAtMs);
        delegates = delegates == null ? List.of() : List.copyOf(delegates);
        delegateIndex = Math.max(0, delegateIndex);
        ragContext = ragContext == null || ragContext.isBlank() ? "None." : ragContext;
        lastStepOutput = lastStepOutput == null ? "" : lastStepOutput;
        delegateOutputs = immutableLinkedMap(delegateOutputs);
        allSources = allSources == null ? List.of() : List.copyOf(allSources);
        gatewayMemory = gatewayMemory == null ? List.of() : List.copyOf(gatewayMemory);
    }

    static DefaultAgentRunState initial(AgentRequest request, AgentSystem system, ActorRef<AgentResult> replyTo) {
        return new DefaultAgentRunState(
                request,
                system,
                replyTo,
                System.currentTimeMillis(),
                delegatesFor(system),
                0,
                "None.",
                "",
                Map.of(),
                List.of(),
                List.of());
    }

    DefaultAgentRunState withRag(String ragContext, List<String> sources) {
        return new DefaultAgentRunState(
                request,
                system,
                replyTo,
                startedAtMs,
                delegates,
                delegateIndex,
                ragContext,
                lastStepOutput,
                delegateOutputs,
                append(allSources, sources),
                gatewayMemory);
    }

    DefaultAgentRunState advanceDelegate() {
        return new DefaultAgentRunState(
                request,
                system,
                replyTo,
                startedAtMs,
                delegates,
                delegateIndex + 1,
                ragContext,
                lastStepOutput,
                delegateOutputs,
                allSources,
                gatewayMemory);
    }

    DefaultAgentRunState withStepResult(AgentStepActor.Result result) {
        Map<String, String> outputs = new LinkedHashMap<>(delegateOutputs);
        outputs.put(result.agentName(), result.output());
        return new DefaultAgentRunState(
                request,
                system,
                replyTo,
                startedAtMs,
                delegates,
                delegateIndex,
                ragContext,
                result.output(),
                outputs,
                append(allSources, result.sources()),
                gatewayMemory);
    }

    DefaultAgentRunState withGatewayMemory(List<AgentMemoryEvent> memory) {
        return new DefaultAgentRunState(
                request,
                system,
                replyTo,
                startedAtMs,
                delegates,
                delegateIndex,
                ragContext,
                lastStepOutput,
                delegateOutputs,
                allSources,
                memory);
    }

    List<String> distinctSources() {
        return allSources.stream().distinct().toList();
    }

    private static List<Agent> delegatesFor(AgentSystem system) {
        Map<String, Agent> byName = system.agents().stream()
                .collect(Collectors.toMap(Agent::name, agent -> agent, (left, right) -> left));
        return system.entrypoint().delegates().stream()
                .map(byName::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Map<String, String> immutableLinkedMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static List<String> append(List<String> existing, List<String> additions) {
        if (additions == null || additions.isEmpty()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<String> merged = new java.util.ArrayList<>(existing == null ? List.of() : existing);
        merged.addAll(additions);
        return List.copyOf(merged);
    }
}
