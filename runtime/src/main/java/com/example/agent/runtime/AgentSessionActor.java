package com.example.agent.runtime;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.prompts.PromptTemplates;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.tool.ToolCatalog;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.runtime.telemetry.Telemetry;
import io.opentelemetry.api.trace.Span;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentSessionActor extends AbstractBehavior<AgentSessionActor.Command> {
    public sealed interface Command permits Start, WrappedLlmResponse, WrappedToolResult, WrappedPolicyDecision, ToolTimeout, WorkflowTimeout {
    }

    public record Start(AgentRequest request, ActorRef<AgentResult> replyTo) implements Command {
    }

    private record WrappedLlmResponse(LlmProtocol.Response response) implements Command {
    }

    private record WrappedToolResult(ToolProtocol.ToolResult result) implements Command {
    }

    private record WrappedPolicyDecision(PolicyActor.PolicyDecision decision) implements Command {
    }

    private record ToolTimeout(String toolRequestId) implements Command {
    }

    private record WorkflowTimeout() implements Command {
    }

    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final ActorRef<PolicyActor.Command> policyActor;
    private final List<String> enabledTools;
    private final int maxTools;
    private final int maxSteps;
    private final Duration workflowTimeout;
    private final Duration toolTimeout;
    private final List<String> observations = new ArrayList<>();
    private final List<String> sourceUrls = new ArrayList<>();
    private AgentRequest request;
    private ActorRef<AgentResult> replyTo;
    private int stepNumber;
    private int toolCalls;
    private String pendingToolRequestId;
    private ActionStep pendingAction;
    private Span sessionSpan;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            List<String> enabledTools,
            int maxTools,
            int maxSteps,
            Duration workflowTimeout,
            Duration toolTimeout
    ) {
        return Behaviors.setup(context -> new AgentSessionActor(
                context,
                llmWorker,
                toolRegistry,
                context.spawn(PolicyActor.create(), "policy-" + context.getSelf().path().name()),
                enabledTools,
                maxTools,
                maxSteps,
                workflowTimeout,
                toolTimeout
        ));
    }

    private AgentSessionActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            ActorRef<PolicyActor.Command> policyActor,
            List<String> enabledTools,
            int maxTools,
            int maxSteps,
            Duration workflowTimeout,
            Duration toolTimeout
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.policyActor = Objects.requireNonNull(policyActor);
        this.enabledTools = List.copyOf(enabledTools);
        this.maxTools = Math.max(0, maxTools);
        this.maxSteps = Math.max(1, maxSteps);
        this.workflowTimeout = Objects.requireNonNull(workflowTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .onMessage(WrappedLlmResponse.class, this::onLlmResponse)
                .onMessage(WrappedToolResult.class, this::onToolResult)
                .onMessage(WrappedPolicyDecision.class, this::onPolicyDecision)
                .onMessage(ToolTimeout.class, this::onToolTimeout)
                .onMessage(WorkflowTimeout.class, this::onWorkflowTimeout)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        this.request = start.request();
        this.replyTo = start.replyTo();
        this.sessionSpan = Telemetry.startInternalSpan("agent.session");
        this.sessionSpan.setAttribute("agent.request_id", request.requestId());
        getContext().scheduleOnce(workflowTimeout, getContext().getSelf(), new WorkflowTimeout());
        return requestStep();
    }

    private Behavior<Command> requestStep() {
        if (stepNumber >= maxSteps) {
            return requestFinalize();
        }
        stepNumber++;
        Span llmSpan = Telemetry.startInternalSpan("agent.llm.step");
        llmSpan.setAttribute("agent.step", stepNumber);
        ActorRef<LlmProtocol.Response> adapter = getContext().messageAdapter(LlmProtocol.Response.class, WrappedLlmResponse::new);
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":session:step:" + stepNumber,
                PromptTemplates.reactPrompt(request.input(), allowedToolsText(), stepNumber, maxSteps, observationsText()),
                adapter
        ));
        llmSpan.end();
        return this;
    }

    private Behavior<Command> requestFinalize() {
        ActorRef<LlmProtocol.Response> adapter = getContext().messageAdapter(LlmProtocol.Response.class, WrappedLlmResponse::new);
        Span llmFinalSpan = Telemetry.startInternalSpan("agent.llm.finalize");
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":session:final",
                PromptTemplates.reactFinalPrompt(request.input(), observationsText()),
                adapter
        ));
        llmFinalSpan.end();
        return this;
    }

    private Behavior<Command> onLlmResponse(WrappedLlmResponse wrapped) {
        LlmProtocol.Response response = wrapped.response();
        if (!response.isSuccess()) {
            return finish(AgentStatus.FAILED_SYSTEM, "", List.of(new AgentError("llm_error", safeMessage(response.error()), true, "llm")));
        }
        if (response.requestId().endsWith(":final")) {
            FinalStep finalStep = parseFinal(response.text());
            if (finalStep == null) {
                return finish(AgentStatus.DEGRADED, summarizeObservations(), List.of(
                        new AgentError("invalid_final", "Model did not return FINAL format; degraded synthesis used.", false, "workflow")
                ));
            }
            return finish(AgentStatus.COMPLETED, withSources(finalStep.answer()), List.of());
        }
        ActionStep step = parseAction(response.text());
        if (step == null) {
            observations.add("Invalid model step: " + compact(response.text()));
            return requestStep();
        }
        pendingAction = step;
        ActorRef<PolicyActor.PolicyDecision> policyAdapter = getContext().messageAdapter(
                PolicyActor.PolicyDecision.class,
                WrappedPolicyDecision::new
        );
        policyActor.tell(new PolicyActor.EvaluateAction(
                step.toolName(),
                step.query().isBlank() ? request.input() : step.query(),
                enabledTools,
                toolCalls,
                maxTools,
                policyAdapter
        ));
        return this;
    }

    private Behavior<Command> onPolicyDecision(WrappedPolicyDecision wrapped) {
        PolicyActor.PolicyDecision decision = wrapped.decision();
        if (!decision.allowed()) {
            observations.add("Policy rejected action: " + decision.reason());
            if ("tool_budget_exhausted".equals(decision.reason())) {
                return requestFinalize();
            }
            return requestStep();
        }
        toolCalls++;
        ActorRef<ToolProtocol.ToolResult> toolAdapter = getContext().messageAdapter(ToolProtocol.ToolResult.class, WrappedToolResult::new);
        pendingToolRequestId = request.requestId() + ":session:tool:" + toolCalls + ":" + pendingAction.toolName();
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout(pendingToolRequestId));
        toolRegistry.tell(new ToolProtocol.InvokeTool(
                pendingToolRequestId,
                pendingAction.toolName(),
                ToolCatalog.defaultArguments(pendingAction.toolName(), request.input(), pendingAction.query()),
                toolAdapter
        ));
        Span toolSpan = Telemetry.startInternalSpan("agent.tool.invoke");
        toolSpan.setAttribute("agent.tool", pendingAction.toolName());
        toolSpan.setAttribute("agent.tool_call", toolCalls);
        toolSpan.end();
        return this;
    }

    private Behavior<Command> onToolResult(WrappedToolResult wrapped) {
        ToolProtocol.ToolResult result = wrapped.result();
        if (pendingToolRequestId == null || !pendingToolRequestId.equals(result.requestId())) {
            return this;
        }
        pendingToolRequestId = null;
        if (result.isSuccess()) {
            observations.add("OBSERVATION " + toolCalls + " from " + result.toolName() + ":\n" + result.output());
            sourceUrls.addAll(sourceUrls(result.output()));
        } else {
            observations.add("OBSERVATION " + toolCalls + " from " + result.toolName() + ": failed: " + safeMessage(result.error()));
        }
        if (toolCalls >= maxTools) {
            return requestFinalize();
        }
        return requestStep();
    }

    private Behavior<Command> onToolTimeout(ToolTimeout timeout) {
        if (pendingToolRequestId == null || !pendingToolRequestId.equals(timeout.toolRequestId())) {
            return this;
        }
        pendingToolRequestId = null;
        observations.add("OBSERVATION " + toolCalls + ": tool timed out after " + toolTimeout);
        return requestStep();
    }

    private Behavior<Command> onWorkflowTimeout(WorkflowTimeout timeout) {
        return finish(
                AgentStatus.TIMEOUT,
                summarizeObservations(),
                List.of(new AgentError("workflow_timeout", "Workflow timed out after " + workflowTimeout, true, "workflow"))
        );
    }

    private Behavior<Command> finish(AgentStatus status, String output, List<AgentError> errors) {
        if (sessionSpan != null) {
            sessionSpan.setAttribute("agent.status", status.name());
            if (!errors.isEmpty()) {
                sessionSpan.setAttribute("agent.error_count", errors.size());
            }
            sessionSpan.end();
        }
        replyTo.tell(new AgentResult(request.requestId(), status, output, sourceUrls.stream().distinct().toList(), errors));
        return Behaviors.stopped();
    }

    private String allowedToolsText() {
        if (enabledTools.isEmpty() || maxTools == 0) {
            return "- none";
        }
        return enabledTools.stream().limit(maxTools).map(ToolCatalog::promptDescription).collect(Collectors.joining("\n"));
    }

    private String observationsText() {
        if (observations.isEmpty()) {
            return "None.";
        }
        return observations.stream().map(AgentSessionActor::compact).collect(Collectors.joining("\n\n"));
    }

    private String summarizeObservations() {
        if (observations.isEmpty()) {
            return "No observations collected.";
        }
        return observations.stream().limit(4).collect(Collectors.joining("\n"));
    }

    private String withSources(String answer) {
        if (sourceUrls.isEmpty()) {
            return answer;
        }
        List<String> distinct = sourceUrls.stream().distinct().toList();
        boolean hasAny = distinct.stream().anyMatch(answer::contains);
        if (hasAny) {
            return answer;
        }
        return answer.stripTrailing() + "\n\nSources:\n" + distinct.stream().map(url -> "- " + url).collect(Collectors.joining("\n"));
    }

    private static ActionStep parseAction(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String trimmed = output.trim();
        String firstLine = trimmed.lines().findFirst().orElse("").trim();
        if (!"ACTION".equalsIgnoreCase(firstLine)) {
            return null;
        }
        String toolName = "";
        String query = "";
        for (String line : trimmed.lines().skip(1).toList()) {
            String normalized = line.trim();
            int equals = normalized.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = normalized.substring(0, equals).trim().toLowerCase();
            String value = normalized.substring(equals + 1).trim();
            if ("tool".equals(key)) {
                toolName = value.toLowerCase().replace("\"", "");
            } else if ("query".equals(key) || "input".equals(key)) {
                query = value;
            }
        }
        return toolName.isBlank() ? null : new ActionStep(toolName, query);
    }

    private static FinalStep parseFinal(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String trimmed = output.trim();
        String firstLine = trimmed.lines().findFirst().orElse("").trim();
        if ("FINAL".equalsIgnoreCase(firstLine)) {
            String answer = trimmed.substring(firstLine.length()).trim();
            return answer.isBlank() ? null : new FinalStep(answer);
        }
        if (firstLine.toLowerCase().startsWith("final")) {
            int separator = Math.max(trimmed.indexOf('\n'), trimmed.indexOf(':'));
            String answer = separator >= 0 ? trimmed.substring(separator + 1).trim() : "";
            return answer.isBlank() ? null : new FinalStep(answer);
        }
        return null;
    }

    private static List<String> sourceUrls(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            return List.of();
        }
        return toolOutput.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("URL:"))
                .map(line -> line.substring("URL:".length()).trim())
                .filter(url -> !url.isBlank())
                .filter(url -> !"<unknown>".equals(url))
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static String compact(String value) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() <= 800 ? stripped : stripped.substring(0, 800) + "...";
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "<no error message>" : error.getMessage();
    }

    private record ActionStep(String toolName, String query) {
    }

    private record FinalStep(String answer) {
    }
}
