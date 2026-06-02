package com.example.agent.workflow;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.prompts.PromptTemplates;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import com.example.agent.tool.ToolCatalog;
import com.example.agent.tool.ToolProtocol;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ReActWorkflowActor extends AbstractBehavior<ReActWorkflowActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final List<String> enabledTools;
    private final int maxTools;
    private final int maxSteps;
    private final Duration workflowTimeout;
    private final Duration toolTimeout;
    private final int maxToolRetries;
    private final List<String> observations = new ArrayList<>();
    private final List<String> sourceUrls = new ArrayList<>();
    private final Set<String> attemptedTools = new HashSet<>();
    private final Set<String> attemptedActionSignatures = new HashSet<>();
    private AgentRequest request;
    private ActorRef<AgentResponse> replyTo;
    private int stepNumber;
    private int toolCalls;
    private String pendingToolRequestId;
    private ActionStep pendingActionStep;
    private int pendingToolAttempt;
    private boolean finalOnly;
    private boolean requiresSources;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            String enabledTools,
            int maxTools,
            int maxSteps,
            Duration workflowTimeout,
            Duration toolTimeout,
            int maxToolRetries
    ) {
        return Behaviors.setup(context -> new ReActWorkflowActor(
                context,
                llmWorker,
                toolRegistry,
                parseEnabledTools(enabledTools),
                maxTools,
                maxSteps,
                workflowTimeout,
                toolTimeout,
                maxToolRetries
        ));
    }

    private ReActWorkflowActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            List<String> enabledTools,
            int maxTools,
            int maxSteps,
            Duration workflowTimeout,
            Duration toolTimeout,
            int maxToolRetries
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.enabledTools = List.copyOf(enabledTools);
        this.maxTools = Math.max(0, maxTools);
        this.maxSteps = Math.max(1, maxSteps);
        this.workflowTimeout = Objects.requireNonNull(workflowTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
        this.maxToolRetries = Math.max(0, maxToolRetries);
    }

    public sealed interface Command permits Start, WrappedLlmResponse, WrappedToolResult, ToolTimeout, WorkflowTimeout, RetryTool {
    }

    public record Start(
            AgentRequest request,
            ActorRef<AgentResponse> replyTo
    ) implements Command {
    }

    private record WrappedLlmResponse(LlmProtocol.Response response) implements Command {
    }

    private record WrappedToolResult(ToolProtocol.ToolResult result) implements Command {
    }

    private record ToolTimeout(String toolRequestId) implements Command {
    }

    private record WorkflowTimeout() implements Command {
    }

    private record RetryTool(ActionStep actionStep) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .onMessage(WrappedLlmResponse.class, this::onWrappedLlmResponse)
                .onMessage(WrappedToolResult.class, this::onWrappedToolResult)
                .onMessage(ToolTimeout.class, this::onToolTimeout)
                .onMessage(WorkflowTimeout.class, this::onWorkflowTimeout)
                .onMessage(RetryTool.class, this::onRetryTool)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        this.request = start.request();
        this.replyTo = start.replyTo();
        this.requiresSources = requiresSources(request.input());
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                "started",
                "tools=" + enabledTools + " max_tools=" + maxTools + " max_steps=" + maxSteps
                        + " requires_sources=" + requiresSources
        );
        getContext().scheduleOnce(workflowTimeout, getContext().getSelf(), new WorkflowTimeout());
        return requestNextStep();
    }

    private Behavior<Command> onWrappedLlmResponse(WrappedLlmResponse wrapped) {
        LlmProtocol.Response response = wrapped.response();
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "llm_response",
                "correlation_id=" + response.requestId() + " success=" + response.isSuccess()
        );
        if (!response.isSuccess()) {
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "failed",
                    "phase=llm correlation_id=" + response.requestId()
            );
            replyTo.tell(new AgentResponse(request.requestId(), response.text(), response.error()));
            return Behaviors.stopped();
        }

        ReActStep step = parseStep(response.text());
        if (step instanceof FinalStep finalStep) {
            if (requiresSources && sourceUrls.isEmpty()) {
                WorkflowLogger.event(
                        getContext(),
                        "react",
                        request.requestId(),
                        stepNumber,
                        "source_required",
                        "reason=final_without_sources"
                );
                return requestSourceToolOrFail("final answer did not include collected source evidence");
            }
            replyTo.tell(new AgentResponse(request.requestId(), withSources(finalStep.answer()), null));
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "final",
                    "success=true sources=" + sourceUrls.stream().distinct().count()
            );
            return Behaviors.stopped();
        }

        if (step instanceof ActionStep actionStep) {
            if (finalOnly) {
                WorkflowLogger.event(
                        getContext(),
                        "react",
                        request.requestId(),
                        stepNumber,
                        "failed",
                        "reason=action_in_final_only tool=" + actionStep.toolName()
                );
                return finishFromObservations();
            }
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "llm_decision",
                    "outcome=action tool=" + actionStep.toolName()
            );
            return invokeTool(actionStep);
        }

        observations.add("Invalid model step: " + compact(response.text()));
        if (finalOnly) {
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "failed",
                    "reason=invalid_final_output"
            );
            return finishFromObservations();
        }
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "llm_decision",
                "outcome=invalid"
        );
        return requestNextStep();
    }

    private Behavior<Command> invokeTool(ActionStep actionStep) {
        String actionSignature = actionSignature(actionStep);
        if (!attemptedActionSignatures.add(actionSignature)) {
            observations.add("Duplicate tool call suppressed: " + actionStep.toolName() + " query=" + compact(actionStep.query()));
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "tool_rejected",
                    "tool=" + actionStep.toolName() + " reason=duplicate_action"
            );
            return requestFinalStep();
        }
        if (!enabledTools.contains(actionStep.toolName())) {
            observations.add("Rejected tool call: " + actionStep.toolName() + " is not enabled.");
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "tool_rejected",
                    "tool=" + actionStep.toolName() + " reason=not_enabled"
            );
            return requestNextStep();
        }
        if (toolCalls >= maxTools) {
            if (observations.stream().noneMatch(observation -> observation.startsWith("Tool budget exhausted"))) {
                observations.add("Tool budget exhausted after " + toolCalls + " call(s).");
            }
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "tool_budget_exhausted",
                    "tool_calls=" + toolCalls + " max_tools=" + maxTools
            );
            return requestFinalStep();
        }

        toolCalls++;
        attemptedTools.add(actionStep.toolName());
        pendingActionStep = actionStep;
        pendingToolAttempt = 1;
        pendingToolRequestId = request.requestId() + ":react:tool:" + toolCalls + ":" + actionStep.toolName() + ":attempt:1";
        ActorRef<ToolProtocol.ToolResult> toolAdapter = getContext().messageAdapter(
                ToolProtocol.ToolResult.class,
                WrappedToolResult::new
        );
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout(pendingToolRequestId));
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "tool_request",
                "tool=" + actionStep.toolName() + " correlation_id=" + pendingToolRequestId
        );
        toolRegistry.tell(new ToolProtocol.InvokeTool(
                pendingToolRequestId,
                actionStep.toolName(),
                toolArguments(actionStep),
                toolAdapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedToolResult(WrappedToolResult wrapped) {
        ToolProtocol.ToolResult result = wrapped.result();
        if (pendingToolRequestId == null || !pendingToolRequestId.equals(result.requestId())) {
            return this;
        }
        pendingToolRequestId = null;

        if (result.isSuccess()) {
            pendingActionStep = null;
            pendingToolAttempt = 0;
            observations.add("OBSERVATION " + toolCalls + " from " + result.toolName() + ":\n" + result.output());
            List<String> urls = sourceUrls(result.output());
            sourceUrls.addAll(urls);
            if (ToolCatalog.isSourceTool(result.toolName())) {
                getContext().getLog().info(
                        "ReAct tool {} resources for request {}: count={} urls={}",
                        result.toolName(),
                        request.requestId(),
                        urls.size(),
                        urls
                );
            }
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "tool_response",
                    "tool=" + result.toolName() + " success=true sources=" + urls.size()
            );
            if (requiresSources && ToolCatalog.isSourceTool(result.toolName())) {
                if (sourceUrls.isEmpty()) {
                    return requestSourceToolOrFail("source tool returned no source URLs");
                }
                return requestFinalStep();
            }
        } else {
            observations.add("OBSERVATION " + toolCalls + " from " + result.toolName()
                    + ": failed: " + result.error().getMessage());
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "tool_response",
                    "tool=" + result.toolName() + " success=false error=" + result.error().getClass().getSimpleName()
            );
            return handleToolFailure(result.toolName(), "failed: " + result.error().getMessage());
        }

        return requestNextStep();
    }

    private Behavior<Command> onToolTimeout(ToolTimeout timeout) {
        if (pendingToolRequestId == null || !pendingToolRequestId.equals(timeout.toolRequestId())) {
            return this;
        }
        observations.add("OBSERVATION " + toolCalls + ": tool timed out after " + toolTimeout);
        pendingToolRequestId = null;
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "tool_timeout",
                "timeout=" + toolTimeout
        );
        if (pendingActionStep != null && pendingToolAttempt <= maxToolRetries) {
            getContext().scheduleOnce(Duration.ofMillis(200L * pendingToolAttempt), getContext().getSelf(), new RetryTool(pendingActionStep));
            return this;
        }
        return handleToolFailure(
                pendingActionStep == null ? "<unknown>" : pendingActionStep.toolName(),
                "timed out after " + toolTimeout
        );
    }

    private Behavior<Command> onWorkflowTimeout(WorkflowTimeout timeout) {
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "workflow_timeout",
                "timeout=" + workflowTimeout
        );
        replyTo.tell(new AgentResponse(
                request.requestId(),
                "",
                new IllegalStateException("ReAct workflow timed out after " + workflowTimeout)
        ));
        return Behaviors.stopped();
    }

    private Behavior<Command> requestNextStep() {
        if (stepNumber >= maxSteps) {
            return requestFinalStep();
        }

        stepNumber++;
        ActorRef<LlmProtocol.Response> responseAdapter = getContext().messageAdapter(
                LlmProtocol.Response.class,
                WrappedLlmResponse::new
        );
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "llm_request",
                "phase=step correlation_id=" + request.requestId() + ":react:step:" + stepNumber
        );
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":react:step:" + stepNumber,
                PromptTemplates.reactPrompt(
                        request.input(),
                        allowedToolsText(),
                        stepNumber,
                        maxSteps,
                        observationsText()
                ),
                responseAdapter
        ));
        return this;
    }

    private Behavior<Command> requestFinalStep() {
        if (finalOnly) {
            return finishFromObservations();
        }

        finalOnly = true;
        if (stepNumber < maxSteps) {
            stepNumber++;
        }
        ActorRef<LlmProtocol.Response> responseAdapter = getContext().messageAdapter(
                LlmProtocol.Response.class,
                WrappedLlmResponse::new
        );
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "llm_request",
                "phase=final correlation_id=" + request.requestId() + ":react:final"
        );
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":react:final",
                PromptTemplates.reactFinalPrompt(request.input(), observationsText()),
                responseAdapter
        ));
        return this;
    }

    private Behavior<Command> finishFromObservations() {
        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "failed",
                "reason=no_final_answer"
        );
        replyTo.tell(new AgentResponse(
                request.requestId(),
                "",
                new IllegalStateException(
                        "ReAct workflow ended without a FINAL answer. Observations:\n" + observationsText()
                )
        ));
        return Behaviors.stopped();
    }

    private Map<String, String> toolArguments(ActionStep actionStep) {
        return ToolCatalog.defaultArguments(actionStep.toolName(), request.input(), actionStep.query());
    }

    private String allowedToolsText() {
        if (enabledTools.isEmpty() || maxTools == 0) {
            return "- none";
        }
        return enabledTools.stream()
                .limit(maxTools)
                .map(ToolCatalog::promptDescription)
                .collect(Collectors.joining("\n"));
    }

    private String observationsText() {
        if (observations.isEmpty()) {
            return "None.";
        }
        return observations.stream()
                .map(ReActWorkflowActor::compact)
                .collect(Collectors.joining("\n\n"));
    }

    private String withSources(String answer) {
        if (sourceUrls.isEmpty()) {
            return stripUnverifiedSourcesSection(answer);
        }

        List<String> distinctUrls = sourceUrls.stream()
                .distinct()
                .toList();
        boolean answerAlreadyHasUrl = distinctUrls.stream().anyMatch(answer::contains);
        if (answerAlreadyHasUrl) {
            return answer;
        }

        String sources = distinctUrls.stream()
                .map(url -> "- " + url)
                .collect(Collectors.joining("\n"));
        return answer.stripTrailing() + "\n\nSources:\n" + sources;
    }

    private static String stripUnverifiedSourcesSection(String answer) {
        int sourcesIndex = answer.lastIndexOf("\nSources:");
        if (sourcesIndex < 0) {
            return answer;
        }
        return answer.substring(0, sourcesIndex).stripTrailing();
    }

    private Behavior<Command> requestSourceToolOrFail(String reason) {
        String toolName = nextSourceTool();
        if (toolName == null) {
            WorkflowLogger.event(
                    getContext(),
                    "react",
                    request.requestId(),
                    stepNumber,
                    "failed",
                    "reason=required_sources_unavailable detail=" + reason
            );
            replyTo.tell(new AgentResponse(
                    request.requestId(),
                    "",
                    new IllegalStateException("ReAct workflow requires source evidence but no source URLs were collected: " + reason)
            ));
            return Behaviors.stopped();
        }

        WorkflowLogger.event(
                getContext(),
                "react",
                request.requestId(),
                stepNumber,
                "source_fallback",
                "tool=" + toolName + " reason=" + reason
        );
        finalOnly = false;
        return invokeTool(new ActionStep(toolName, request.input()));
    }

    private String nextSourceTool() {
        for (String toolName : preferredSourceTools()) {
            if (enabledTools.contains(toolName) && !attemptedTools.contains(toolName) && toolCalls < maxTools) {
                return toolName;
            }
        }
        return null;
    }

    private List<String> preferredSourceTools() {
        return requiresBiomedicalSources(request.input())
                ? List.of(ToolCatalog.WEB_SEARCH, ToolCatalog.ARXIV_SEARCH, ToolCatalog.RAG_RETRIEVE)
                : List.of(ToolCatalog.WEB_SEARCH, ToolCatalog.ARXIV_SEARCH, ToolCatalog.RAG_RETRIEVE);
    }

    private static ReActStep parseStep(String output) {
        if (output == null || output.isBlank()) {
            return new InvalidStep();
        }

        String trimmed = output.trim();
        FinalStep embeddedFinal = extractEmbeddedFinal(trimmed);
        if (embeddedFinal != null) {
            return embeddedFinal;
        }
        String firstLine = trimmed.lines().findFirst().orElse("").trim();
        if ("FINAL".equalsIgnoreCase(firstLine)) {
            return new FinalStep(trimmed.substring(firstLine.length()).trim());
        }
        if (firstLine.toLowerCase().startsWith("final")) {
            int separator = Math.max(trimmed.indexOf('\n'), trimmed.indexOf(':'));
            String answer = separator >= 0 ? trimmed.substring(separator + 1).trim() : "";
            return answer.isBlank() ? new InvalidStep() : new FinalStep(answer);
        }

        if (!"ACTION".equalsIgnoreCase(firstLine)) {
            return new InvalidStep();
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
        return toolName.isBlank() ? new InvalidStep() : new ActionStep(toolName, query);
    }

    private static FinalStep extractEmbeddedFinal(String output) {
        String normalized = output.toLowerCase();
        int finalIndex = normalized.indexOf("\nfinal");
        if (finalIndex < 0) {
            return null;
        }
        String finalBlock = output.substring(finalIndex + 1).trim();
        String[] lines = finalBlock.split("\\R", 2);
        if (lines.length == 0) {
            return null;
        }
        String header = lines[0].trim().toLowerCase();
        if (!header.equals("final") && !header.startsWith("final:")) {
            return null;
        }
        String answer = lines.length == 1
                ? header.startsWith("final:") ? lines[0].substring(lines[0].indexOf(':') + 1).trim() : ""
                : lines[1].trim();
        if (answer.isBlank()) {
            return null;
        }
        return new FinalStep(answer);
    }

    private static String actionSignature(ActionStep actionStep) {
        String tool = actionStep.toolName() == null ? "" : actionStep.toolName().strip().toLowerCase();
        String query = actionStep.query() == null ? "" : actionStep.query().strip().toLowerCase();
        return tool + "::" + query;
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compacted = value.strip();
        return compacted.length() <= 1600 ? compacted : compacted.substring(0, 1600) + "...";
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
                .distinct()
                .toList();
    }

    private Behavior<Command> onRetryTool(RetryTool retryTool) {
        if (pendingActionStep == null || pendingToolRequestId != null) {
            return this;
        }
        pendingToolAttempt++;
        pendingToolRequestId = request.requestId() + ":react:tool:" + toolCalls + ":" + retryTool.actionStep().toolName()
                + ":attempt:" + pendingToolAttempt;
        ActorRef<ToolProtocol.ToolResult> toolAdapter = getContext().messageAdapter(
                ToolProtocol.ToolResult.class,
                WrappedToolResult::new
        );
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout(pendingToolRequestId));
        toolRegistry.tell(new ToolProtocol.InvokeTool(
                pendingToolRequestId,
                retryTool.actionStep().toolName(),
                toolArguments(retryTool.actionStep()),
                toolAdapter
        ));
        return this;
    }

    private Behavior<Command> handleToolFailure(String toolName, String reason) {
        if (pendingActionStep != null && pendingToolAttempt <= maxToolRetries) {
            getContext().scheduleOnce(Duration.ofMillis(200L * pendingToolAttempt), getContext().getSelf(), new RetryTool(pendingActionStep));
            return this;
        }
        pendingActionStep = null;
        pendingToolAttempt = 0;
        if (requiresSources && ToolCatalog.isSourceTool(toolName) && sourceUrls.isEmpty()) {
            String fallbackToolName = nextSourceTool();
            if (fallbackToolName != null) {
                observations.add("Fallback after " + toolName + " " + reason + ": trying " + fallbackToolName);
                return invokeTool(new ActionStep(fallbackToolName, request.input()));
            }
            observations.add("Source evidence unavailable after tool failures: " + toolName + " " + reason);
            return requestFinalStep();
        }
        return requestNextStep();
    }

    private static boolean requiresSources(String input) {
        String normalized = input == null ? "" : input.toLowerCase();
        return requiresBiomedicalSources(normalized)
                || normalized.contains("latest")
                || normalized.contains("recent")
                || normalized.contains("current")
                || normalized.contains("research")
                || normalized.contains("paper")
                || normalized.contains("source")
                || normalized.contains("citation")
                || normalized.contains("cite");
    }

    private static boolean requiresBiomedicalSources(String input) {
        String normalized = input == null ? "" : input.toLowerCase();
        return normalized.contains("treatment")
                || normalized.contains("treatments")
                || normalized.contains("cancer")
                || normalized.contains("carcinoma")
                || normalized.contains("clinical")
                || normalized.contains("therapy")
                || normalized.contains("therapies")
                || normalized.contains("drug")
                || normalized.contains("trial")
                || normalized.contains("disease")
                || normalized.contains("diagnosis")
                || normalized.contains("patient");
    }

    private static List<String> parseEnabledTools(String value) {
        return ToolCatalog.parseEnabledTools(value).stream()
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private sealed interface ReActStep permits ActionStep, FinalStep, InvalidStep {
    }

    private record ActionStep(String toolName, String query) implements ReActStep {
    }

    private record FinalStep(String answer) implements ReActStep {
    }

    private record InvalidStep() implements ReActStep {
    }
}
