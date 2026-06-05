package com.example.agent.runtime.tool;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTaskDefinition;
import com.example.agent.api.AgentTaskRequest;
import com.example.agent.api.AgentTaskRule;
import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolResult;
import com.example.agent.api.GatewayAgent;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentStatus;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentToolRuntimeTest {
    @Test
    void workflowToolIsInvokedWithTenantAndUserInput() {
        CapturingModel model = new CapturingModel("TOOL: custom.echo", "FINAL: used echo", "FINAL: final answer");
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
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(tool),
                            AgentTaskRequest.of("agent.request").instructions("hello").build(),
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
    void workflowToolIsNotInvokedUnlessLlmRequestsIt() {
        CapturingModel model = new CapturingModel("FINAL: no tool needed", "FINAL: final answer");
        AtomicReference<String> observedTenant = new AtomicReference<>();

        AgentToolDefinition tool = AgentToolDefinition.named("custom.echo")
                .describedAs("Echoes the user input.")
                .handledBy(request -> {
                    observedTenant.set(request.tenantId());
                    return CompletableFuture.completedFuture(AgentToolResult.success("echo"));
                })
                .build();

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(tool),
                            AgentTaskRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertNull(observedTenant.get());
    }

    @Test
    void delegatesRunAsSequentialChain() {
        CapturingModel model = new CapturingModel("FINAL: assistant answer", "FINAL: reviewed answer", "FINAL: gateway answer");

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            chainSystem(),
                            AgentTaskRequest.of("agent.request").instructions("original question").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        String reviewerPrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("You are agent \"reviewer\""))
                .findFirst()
                .orElseThrow();
        assertTrue(reviewerPrompt.contains("Original user task:\noriginal question"));
        assertTrue(reviewerPrompt.contains("Current input for this agent:\nassistant answer"));
    }

    @Test
    void unregisteredDeclaredToolBecomesControlledObservation() {
        CapturingModel model = new CapturingModel("TOOL: missing.tool", "FINAL: recovered", "FINAL: final answer");

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system("missing.tool"),
                            AgentTaskRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertTrue(model.prompts().stream().anyMatch(prompt -> prompt.contains("Tool missing.tool failed: Unregistered tool: missing.tool")));
    }

    @Test
    void toolSourcesAreAddedToFinalResult() {
        CapturingModel model = new CapturingModel("TOOL: source.tool", "FINAL: used source", "FINAL: final answer");
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
                .telemetryEnabled(false)
                .build()) {
            result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(tool),
                            AgentTaskRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(List.of("https://example.com/source"), result.sources());
        assertTrue(result.output().contains("https://example.com/source"));
    }

    @Test
    void taskRuleFailureReturnsStructuredErrorBeforeLlmCall() {
        CapturingModel model = new CapturingModel();
        AgentTaskDefinition task = AgentTaskDefinition.named("agent.request")
                .rule(AgentTaskRule.nonEmptyInstructions())
                .build();

        AgentResult result;
        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(task),
                            AgentTaskRequest.of(task).instructions("").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(AgentStatus.FAILED_SYSTEM, result.status());
        assertEquals("invalid_task", result.errors().getFirst().code());
        assertTrue(model.prompts().isEmpty());
    }

    @Test
    void unsupportedTaskReturnsStructuredErrorBeforeLlmCall() {
        CapturingModel model = new CapturingModel();

        AgentResult result;
        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(task()),
                            AgentTaskRequest.of("unknown.task").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(AgentStatus.FAILED_SYSTEM, result.status());
        assertEquals("unsupported_task", result.errors().getFirst().code());
        assertTrue(model.prompts().isEmpty());
    }

    private static AgentSystem system(String toolName) {
        AgentTaskDefinition task = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .accepts(task)
                .uses(toolName)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem system(AgentToolDefinition tool) {
        AgentTaskDefinition task = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .accepts(task)
                .uses(tool)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem system(AgentTaskDefinition task) {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .accepts(task)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem chainSystem() {
        AgentTaskDefinition requestTask = task();
        AgentTaskDefinition reviewTask = AgentTaskDefinition.named("agent.review")
                .describedAs("Review a prior answer.")
                .maxIterations(1)
                .build();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .accepts(requestTask)
                .build();
        Agent reviewer = Agent.named("reviewer")
                .instructedBy("Review the assistant output.")
                .accepts(reviewTask)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(requestTask)
                .delegatesTo(assistant, reviewer)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agents(assistant, reviewer).build();
    }

    private static AgentTaskDefinition task() {
        return AgentTaskDefinition.named("agent.request")
                .describedAs("Handle an agent request.")
                .maxIterations(3)
                .build();
    }

    private static final class CapturingModel implements ChatModel {
        private final List<String> prompts = new CopyOnWriteArrayList<>();
        private final List<String> responses;
        private final AtomicInteger responseIndex = new AtomicInteger();

        private CapturingModel(String... responses) {
            this.responses = responses == null ? List.of() : List.of(responses);
        }

        @Override
        public String chat(String prompt) {
            prompts.add(prompt);
            int index = responseIndex.getAndIncrement();
            if (index < responses.size()) {
                return responses.get(index);
            }
            return "FINAL: final answer";
        }

        List<String> prompts() {
            return prompts;
        }
    }
}
