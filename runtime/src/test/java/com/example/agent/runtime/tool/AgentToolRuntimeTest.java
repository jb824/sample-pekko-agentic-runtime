package com.example.agent.runtime.tool;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTask;
import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolResult;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Task;
import com.example.agent.runtime.AgentResult;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentToolRuntimeTest {
    @Test
    void registeredToolIsInvokedWithTenantAndUserInput() {
        CapturingModel model = new CapturingModel();
        AtomicReference<String> observedTenant = new AtomicReference<>();
        AtomicReference<String> observedInput = new AtomicReference<>();

        AgentToolDefinition tool = AgentToolDefinition.named("custom.echo")
                .describedAs("Echoes the user input.")
                .handledBy(request -> {
                    observedTenant.set(request.tenantId());
                    observedInput.set(request.userInput());
                    return CompletableFuture.completedFuture(AgentToolResult.success("echo: " + request.userInput()));
                })
                .build();

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .tool(tool)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system("custom.echo"),
                            AgentTask.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals("tenant-a", observedTenant.get());
        assertEquals("hello", observedInput.get());
        assertTrue(model.prompts().stream().anyMatch(prompt -> prompt.contains("Tool custom.echo:\necho: hello")));
    }

    @Test
    void unregisteredDeclaredToolBecomesControlledObservation() {
        CapturingModel model = new CapturingModel();

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system("missing.tool"),
                            AgentTask.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertTrue(model.prompts().stream().anyMatch(prompt -> prompt.contains("Tool missing.tool failed: Unregistered tool: missing.tool")));
    }

    @Test
    void toolSourcesAreAddedToFinalResult() {
        CapturingModel model = new CapturingModel();
        AgentToolDefinition tool = AgentToolDefinition.named("source.tool")
                .describedAs("Returns a cited source.")
                .sourceCapable(true)
                .handledBy(request -> CompletableFuture.completedFuture(
                        AgentToolResult.success("source result", List.of("https://example.com/source"))
                ))
                .build();

        AgentResult result;
        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .tool(tool)
                .telemetryEnabled(false)
                .build()) {
            result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system("source.tool"),
                            AgentTask.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(List.of("https://example.com/source"), result.sources());
        assertTrue(result.output().contains("https://example.com/source"));
    }

    private static AgentSystem system(String toolName) {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .uses(toolName)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(Task.of("agent.request").maxIterations(1).build())
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static final class CapturingModel implements ChatModel {
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public String chat(String prompt) {
            prompts.add(prompt);
            return "final answer";
        }

        List<String> prompts() {
            return prompts;
        }
    }
}
