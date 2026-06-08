package com.example.agent.runtime.tool;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalDefinition;
import com.example.agent.api.GoalRequest;
import com.example.agent.api.GoalRule;
import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolResult;
import com.example.agent.api.Tool;
import com.example.agent.api.GatewayAgent;
import com.example.agent.protocol.AgentResult;
import com.example.agent.protocol.AgentStatus;
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
                            GoalRequest.of("agent.request").instructions("hello").build(),
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
    void mixedToolAndFinalAfterObservationCompletesWithoutReinvokingTool() {
        CapturingModel model = new CapturingModel(
                "TOOL: custom.echo",
                """
                        TOOL: custom.echo
                        ARG ignored=ignored

                        FINAL: used echo
                        """,
                "FINAL: gateway answer"
        );
        AtomicInteger invocations = new AtomicInteger();

        AgentToolDefinition tool = AgentToolDefinition.named("custom.echo")
                .describedAs("Echoes the user input.")
                .handledBy(request -> {
                    invocations.incrementAndGet();
                    return CompletableFuture.completedFuture(AgentToolResult.success("echo: " + request.userInput()));
                })
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
                            GoalRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(1, invocations.get());
        assertTrue(result.output().contains("gateway answer"));
        assertEquals(3, model.prompts().size());
    }

    @Test
    void largeToolObservationIsCompactedBeforeNextLlmCall() {
        CapturingModel model = new CapturingModel("TOOL: custom.big", "FINAL: used compacted output", "FINAL: gateway answer");
        String largeOutput = "x".repeat(20_000);

        AgentToolDefinition tool = AgentToolDefinition.named("custom.big")
                .describedAs("Returns a large payload.")
                .handledBy(request -> CompletableFuture.completedFuture(AgentToolResult.success(largeOutput)))
                .build();

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(tool),
                            GoalRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        String secondPrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("Tool custom.big"))
                .findFirst()
                .orElseThrow();
        assertTrue(secondPrompt.contains("[truncated "));
        assertTrue(secondPrompt.length() < largeOutput.length());
    }

    @Test
    void duplicateToolCallReusesPriorObservationWithoutInvokingAgain() {
        CapturingModel model = new CapturingModel(
                "TOOL: custom.echo",
                "TOOL: custom.echo",
                "FINAL: reused prior result",
                "FINAL: gateway answer"
        );
        AtomicInteger invocations = new AtomicInteger();

        AgentToolDefinition tool = AgentToolDefinition.named("custom.echo")
                .describedAs("Echoes the user input.")
                .handledBy(request -> {
                    invocations.incrementAndGet();
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
                            GoalRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(1, invocations.get());
        assertTrue(model.prompts().stream()
                .anyMatch(prompt -> prompt.contains("was already called with these arguments")));
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
                            GoalRequest.of("agent.request").instructions("hello").build(),
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
                            GoalRequest.of("agent.request").instructions("original question").build(),
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
        assertTrue(reviewerPrompt.contains("- Name: agent.request"));
    }

    @Test
    void modelDrivenGatewayChoosesDelegateThenFinalAnswer() {
        CapturingModel model = new CapturingModel(
                "DELEGATE: assistant",
                "FINAL: assistant answer",
                "FINAL: gateway answer"
        );

        AgentResult result;
        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            modelDrivenSystem(),
                            GoalRequest.of("agent.request").instructions("original question").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertTrue(model.prompts().getFirst().contains("Choose the next delegate"));
        assertTrue(model.prompts().stream().anyMatch(prompt -> prompt.contains("You are agent \"assistant\"")));
        assertTrue(model.prompts().stream().noneMatch(prompt -> prompt.contains("You are agent \"reviewer\"")));
        assertTrue(result.output().contains("gateway answer"));
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
                            GoalRequest.of("agent.request").instructions("hello").build(),
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
                            GoalRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(List.of("https://example.com/source"), result.sources());
        assertTrue(result.output().contains("https://example.com/source"));
    }

    @Test
    void annotatedToolRunsThroughAgentToolLoop() {
        CapturingModel model = new CapturingModel("TOOL: currentDate", "FINAL: used date", "FINAL: final answer");

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(model)
                .telemetryEnabled(false)
                .build()) {
            runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            systemWithAnnotatedTool(new DateTools()),
                            GoalRequest.of("agent.request").instructions("what is today's date?").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();
        }

        assertTrue(model.prompts().stream().anyMatch(prompt -> prompt.contains("Tool currentDate:\n2026-06-06")));
    }

    @Test
    void taskRuleFailureReturnsStructuredErrorBeforeLlmCall() {
        CapturingModel model = new CapturingModel();
        GoalDefinition task = GoalDefinition.named("agent.request")
                .rule(GoalRule.nonEmptyInstructions())
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
                            GoalRequest.of(task).instructions("").build(),
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
                            GoalRequest.of("unknown.task").instructions("hello").build(),
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
        GoalDefinition task = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .uses(toolName)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem system(AgentToolDefinition tool) {
        GoalDefinition task = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .uses(tool)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem system(GoalDefinition task) {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem systemWithAnnotatedTool(Object toolSource) {
        GoalDefinition task = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .usesTools(toolSource)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static AgentSystem chainSystem() {
        GoalDefinition requestTask = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .build();
        Agent reviewer = Agent.named("reviewer")
                .instructedBy("Review the assistant output.")
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(requestTask)
                .delegatesTo(assistant, reviewer)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agents(assistant, reviewer).build();
    }

    private static AgentSystem modelDrivenSystem() {
        GoalDefinition requestTask = task();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer directly.")
                .build();
        Agent reviewer = Agent.named("reviewer")
                .instructedBy("Review the assistant output.")
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(requestTask)
                .modelDriven()
                .delegatesTo("assistant", "reviewer")
                .build();
        return AgentSystem.builder().entrypoint(gateway).agents(assistant, reviewer).build();
    }

    private static GoalDefinition task() {
        return GoalDefinition.named("agent.request")
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

    private static final class DateTools {
        @Tool(description = "Return current date in yyyy-MM-dd format")
        private String currentDate() {
            return "2026-06-06";
        }
    }
}
