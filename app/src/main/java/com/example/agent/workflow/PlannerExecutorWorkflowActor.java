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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class PlannerExecutorWorkflowActor extends AbstractBehavior<PlannerExecutorWorkflowActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final List<String> enabledTools;
    private final int maxTools;
    private final Duration workflowTimeout;
    private final Duration toolTimeout;
    private AgentRequest request;
    private ActorRef<AgentResponse> replyTo;
    private String plan;
    private final List<String> toolContext = new ArrayList<>();
    private final List<String> sourceUrls = new ArrayList<>();
    private int pendingTools;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            String enabledTools,
            int maxTools,
            Duration workflowTimeout,
            Duration toolTimeout
    ) {
        return Behaviors.setup(context -> new PlannerExecutorWorkflowActor(
                context,
                llmWorker,
                toolRegistry,
                parseEnabledTools(enabledTools),
                maxTools,
                workflowTimeout,
                toolTimeout
        ));
    }

    private PlannerExecutorWorkflowActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            List<String> enabledTools,
            int maxTools,
            Duration workflowTimeout,
            Duration toolTimeout
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.enabledTools = List.copyOf(enabledTools);
        this.maxTools = Math.max(0, maxTools);
        this.workflowTimeout = Objects.requireNonNull(workflowTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
    }

    public sealed interface Command permits Start, WrappedLlmResponse, WrappedToolResult, ToolTimeout, WorkflowTimeout {
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

    private record ToolTimeout() implements Command {
    }

    private record WorkflowTimeout() implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .onMessage(WrappedLlmResponse.class, this::onWrappedLlmResponse)
                .onMessage(WrappedToolResult.class, this::onWrappedToolResult)
                .onMessage(ToolTimeout.class, this::onToolTimeout)
                .onMessage(WorkflowTimeout.class, this::onWorkflowTimeout)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        this.request = start.request();
        this.replyTo = start.replyTo();
        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "started",
                "tools=" + enabledTools + " max_tools=" + maxTools
        );
        getContext().scheduleOnce(workflowTimeout, getContext().getSelf(), new WorkflowTimeout());

        ActorRef<LlmProtocol.Response> responseAdapter = getContext().messageAdapter(
                LlmProtocol.Response.class,
                WrappedLlmResponse::new
        );

        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "llm_request",
                "phase=plan correlation_id=" + request.requestId() + ":plan"
        );
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":plan",
                PromptTemplates.plannerPrompt(request.input(), ToolCatalog.promptDescriptions(enabledTools)),
                responseAdapter
        ));

        return this;
    }

    private Behavior<Command> onWrappedLlmResponse(WrappedLlmResponse wrapped) {
        LlmProtocol.Response response = wrapped.response();
        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "llm_response",
                "correlation_id=" + response.requestId() + " success=" + response.isSuccess()
        );
        if (!response.isSuccess()) {
            WorkflowLogger.event(
                    getContext(),
                    "planner-executor",
                    request.requestId(),
                    "failed",
                    "phase=llm correlation_id=" + response.requestId()
            );
            replyTo.tell(new AgentResponse(request.requestId(), response.text(), response.error()));
            return Behaviors.stopped();
        }

        if (response.requestId().endsWith(":plan")) {
            return onPlanResponse(response);
        }

        if (response.requestId().endsWith(":execute")) {
            replyTo.tell(new AgentResponse(request.requestId(), withSources(response.text()), null));
            WorkflowLogger.event(
                    getContext(),
                    "planner-executor",
                    request.requestId(),
                    "final",
                    "success=true sources=" + sourceUrls.stream().distinct().count()
            );
            return Behaviors.stopped();
        }

        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "failed",
                "reason=unexpected_llm_correlation_id correlation_id=" + response.requestId()
        );
        replyTo.tell(new AgentResponse(
                request.requestId(),
                "",
                new IllegalStateException("Unexpected LLM correlation ID: " + response.requestId())
        ));
        return Behaviors.stopped();
    }

    private Behavior<Command> onPlanResponse(LlmProtocol.Response response) {
        this.plan = response.text();
        List<String> selectedTools = selectTools(plan, request.input());
        getContext().getLog().info(
                "Policy selected tools {} for request {}",
                selectedTools,
                request.requestId()
        );
        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "plan_response",
                "selected_tools=" + selectedTools
        );
        if (selectedTools.isEmpty()) {
            return requestExecution();
        }

        ActorRef<ToolProtocol.ToolResult> toolAdapter = getContext().messageAdapter(
                ToolProtocol.ToolResult.class,
                WrappedToolResult::new
        );

        pendingTools = selectedTools.size();
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout());
        for (String toolName : selectedTools) {
            WorkflowLogger.event(
                    getContext(),
                    "planner-executor",
                    request.requestId(),
                    "tool_request",
                    "tool=" + toolName + " correlation_id=" + request.requestId() + ":tool:" + toolName
            );
            toolRegistry.tell(new ToolProtocol.InvokeTool(
                    request.requestId() + ":tool:" + toolName,
                    toolName,
                    toolArguments(toolName),
                    toolAdapter
            ));
        }

        return this;
    }

    private Behavior<Command> onWrappedToolResult(WrappedToolResult wrapped) {
        if (pendingTools <= 0) {
            return this;
        }
        ToolProtocol.ToolResult result = wrapped.result();
        if (result.isSuccess()) {
            toolContext.add(result.toolName() + " returned:\n" + result.output());
            List<String> urls = sourceUrls(result.output());
            sourceUrls.addAll(urls);
            WorkflowLogger.event(
                    getContext(),
                    "planner-executor",
                    request.requestId(),
                    "tool_response",
                    "tool=" + result.toolName() + " success=true sources=" + urls.size()
            );
            getContext().getLog().info(
                    "Tool {} resources for request {}: count={} urls={}",
                    result.toolName(),
                    request.requestId(),
                    urls.size(),
                    urls
            );
        } else {
            toolContext.add(result.toolName() + " failed: " + result.error().getMessage());
            WorkflowLogger.event(
                    getContext(),
                    "planner-executor",
                    request.requestId(),
                    "tool_response",
                    "tool=" + result.toolName() + " success=false error=" + result.error().getClass().getSimpleName()
            );
        }

        pendingTools--;
        if (pendingTools > 0) {
            return this;
        }

        return requestExecution();
    }

    private Behavior<Command> onToolTimeout(ToolTimeout timeout) {
        if (pendingTools <= 0) {
            return this;
        }
        toolContext.add("tool timeout: " + pendingTools + " tool(s) did not respond within " + toolTimeout);
        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "tool_timeout",
                "pending_tools=" + pendingTools + " timeout=" + toolTimeout
        );
        pendingTools = 0;
        return requestExecution();
    }

    private Behavior<Command> onWorkflowTimeout(WorkflowTimeout timeout) {
        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "workflow_timeout",
                "timeout=" + workflowTimeout
        );
        replyTo.tell(new AgentResponse(
                request.requestId(),
                "",
                new IllegalStateException("Workflow timed out after " + workflowTimeout)
        ));
        return Behaviors.stopped();
    }

    private Behavior<Command> requestExecution() {
        ActorRef<LlmProtocol.Response> responseAdapter = getContext().messageAdapter(
                LlmProtocol.Response.class,
                WrappedLlmResponse::new
        );

        WorkflowLogger.event(
                getContext(),
                "planner-executor",
                request.requestId(),
                "llm_request",
                "phase=execute correlation_id=" + request.requestId() + ":execute"
        );
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":execute",
                PromptTemplates.executorPrompt(request.input(), plan, toolContext()),
                responseAdapter
        ));

        return this;
    }

    private Map<String, String> toolArguments(String toolName) {
        return ToolCatalog.defaultArguments(toolName, request.input(), null);
    }

    private List<String> selectTools(String plannerOutput, String userInput) {
        Set<String> requested = new LinkedHashSet<>();
        requested.addAll(toolsFromPlanner(plannerOutput));
        if (requested.isEmpty()) {
            requested.addAll(toolsFromHeuristic(userInput));
        }

        return requested.stream()
                .filter(enabledTools::contains)
                .limit(maxTools)
                .toList();
    }

    private static List<String> toolsFromPlanner(String plannerOutput) {
        if (plannerOutput == null || plannerOutput.isBlank()) {
            return List.of();
        }
        return plannerOutput.lines()
                .filter(line -> line.trim().toLowerCase().startsWith("tools:"))
                .findFirst()
                .map(line -> line.substring(line.indexOf(':') + 1))
                .map(PlannerExecutorWorkflowActor::parseEnabledTools)
                .orElse(List.of());
    }

    private static List<String> toolsFromHeuristic(String userInput) {
        String input = userInput == null ? "" : userInput.toLowerCase();
        Set<String> tools = new LinkedHashSet<>();
        if (input.contains("time") || input.contains("date") || input.contains("today") || input.contains("now")) {
            tools.add(ToolCatalog.TIME_NOW);
        }
        if (input.contains("recent") || input.contains("latest") || input.contains("web") || input.contains("current")) {
            tools.add(ToolCatalog.WEB_SEARCH);
        }
        if (input.contains("arxiv") || input.contains("paper") || input.contains("research")) {
            tools.add(ToolCatalog.ARXIV_SEARCH);
        }
        if (input.contains("pubmed") || input.contains("treatment") || input.contains("cancer")
                || input.contains("disease") || input.contains("clinical") || input.contains("drug")
                || input.contains("therapy") || input.contains("trial")) {
            tools.add(ToolCatalog.PUBMED_SEARCH);
        }
        return List.copyOf(tools);
    }

    private String toolContext() {
        if (toolContext.isEmpty()) {
            return "No tools were invoked.";
        }
        return String.join("\n\n", toolContext);
    }

    private String withSources(String answer) {
        if (sourceUrls.isEmpty()) {
            return stripUnverifiedSourcesSection(answer);
        }

        boolean answerAlreadyHasUrl = sourceUrls.stream().anyMatch(answer::contains);
        if (answerAlreadyHasUrl) {
            return answer;
        }

        String sources = sourceUrls.stream()
                .distinct()
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

    private static List<String> parseEnabledTools(String value) {
        return ToolCatalog.parseEnabledTools(value);
    }
}
