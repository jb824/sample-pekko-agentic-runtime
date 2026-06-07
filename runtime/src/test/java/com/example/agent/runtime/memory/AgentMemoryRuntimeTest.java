package com.example.agent.runtime.memory;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalRequest;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Goal;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentMemoryRuntimeTest {
    @Test
    void recallsPreviousEventsForSameTenantAndAgent() {
        CapturingModel model = new CapturingModel();
        AgentMemoryStore memoryStore = new InMemoryAgentMemoryStore();

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .memoryStore(memoryStore)
                .telemetryEnabled(false)
                .build()) {
            AgentSystem system = singleAgentSystem(AgentMemoryConfig.recentEvents(10));
            GoalRequest first = GoalRequest.of("agent.request").instructions("remember alpha").build();
            GoalRequest second = GoalRequest.of("agent.request").instructions("use prior memory").build();

            runtime.run(AgentRunContext.tenant("tenant-a"), "request-1", system, first, Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .join();
            runtime.run(AgentRunContext.tenant("tenant-a"), "request-2", system, second, Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .join();
        }

        String secondDelegatePrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("You are agent \"assistant\""))
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertTrue(secondDelegatePrompt.contains("[USER_TASK] remember alpha"));
        assertTrue(secondDelegatePrompt.contains("[AGENT_OUTPUT] model-response-1"));
    }

    @Test
    void isolatesMemoryByTenant() {
        CapturingModel model = new CapturingModel();
        AgentMemoryStore memoryStore = new InMemoryAgentMemoryStore();

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .memoryStore(memoryStore)
                .telemetryEnabled(false)
                .build()) {
            AgentSystem system = singleAgentSystem(AgentMemoryConfig.recentEvents(10));

            runtime.run(AgentRunContext.tenant("tenant-a"), "request-1", system,
                            GoalRequest.of("agent.request").instructions("tenant-a secret").build(), Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .join();
            runtime.run(AgentRunContext.tenant("tenant-b"), "request-2", system,
                            GoalRequest.of("agent.request").instructions("tenant-b request").build(), Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .join();
        }

        String tenantBDelegatePrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("You are agent \"assistant\""))
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertFalse(tenantBDelegatePrompt.contains("tenant-a secret"));
        assertTrue(tenantBDelegatePrompt.contains("- Memory from previous events:\nNone."));
    }

    private static AgentSystem singleAgentSystem(AgentMemoryConfig memory) {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .memory(memory)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(Goal.of("agent.request").maxIterations(1).build())
                .delegatesTo(assistant)
                .memory(memory)
                .build();
        return AgentSystem.builder()
                .entrypoint(gateway)
                .agent(assistant)
                .build();
    }

    private static final class CapturingModel implements ChatModel {
        private final AtomicInteger responses = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public String chat(String prompt) {
            prompts.add(prompt);
            return "model-response-" + responses.incrementAndGet();
        }

        List<String> prompts() {
            return prompts;
        }
    }
}
