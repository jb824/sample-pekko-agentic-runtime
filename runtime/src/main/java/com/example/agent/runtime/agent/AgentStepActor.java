package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentTaskDefinition;
import com.example.agent.api.AgentToolDefinition;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.memory.AgentMemoryEvent;
import com.example.agent.runtime.memory.AgentMemoryEventType;
import com.example.agent.runtime.memory.AgentMemoryKey;
import com.example.agent.runtime.memory.AgentMemoryRegistryActor;
import com.example.agent.runtime.tool.ToolProtocol;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentStepActor extends AbstractBehavior<AgentStepActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry;
    private final AgentRequest request;
    private final String gatewayName;
    private final Agent agent;
    private final AgentTaskDefinition task;
    private final String originalInput;
    private final String stepInput;
    private final String retrievedKnowledgeContext;
    private final List<AgentToolDefinition> toolDefinitions;
    private final Duration toolTimeout;
    private final ActorRef<Result> replyTo;
    private final List<String> observations = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();
    private List<AgentMemoryEvent> recalledMemory = List.of();
    private int iteration;
    private ToolCallParser.ToolCall pendingTool;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry,
            AgentRequest request,
            String gatewayName,
            Agent agent,
            AgentTaskDefinition task,
            String originalInput,
            String stepInput,
            String retrievedKnowledgeContext,
            List<AgentToolDefinition> toolDefinitions,
            Duration toolTimeout,
            ActorRef<Result> replyTo
    ) {
        return Behaviors.setup(context -> new AgentStepActor(
                context,
                llmWorker,
                toolRegistry,
                memoryRegistry,
                request,
                gatewayName,
                agent,
                task,
                originalInput,
                stepInput,
                retrievedKnowledgeContext,
                toolDefinitions,
                toolTimeout,
                replyTo
        ));
    }

    private AgentStepActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry,
            AgentRequest request,
            String gatewayName,
            Agent agent,
            AgentTaskDefinition task,
            String originalInput,
            String stepInput,
            String retrievedKnowledgeContext,
            List<AgentToolDefinition> toolDefinitions,
            Duration toolTimeout,
            ActorRef<Result> replyTo
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.memoryRegistry = Objects.requireNonNull(memoryRegistry);
        this.request = Objects.requireNonNull(request);
        this.gatewayName = Objects.requireNonNull(gatewayName);
        this.agent = Objects.requireNonNull(agent);
        this.task = Objects.requireNonNull(task);
        this.originalInput = originalInput == null ? "" : originalInput;
        this.stepInput = stepInput == null ? "" : stepInput;
        this.retrievedKnowledgeContext = retrievedKnowledgeContext == null || retrievedKnowledgeContext.isBlank()
                ? "None."
                : retrievedKnowledgeContext;
        this.toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
        this.replyTo = Objects.requireNonNull(replyTo);
    }

    public sealed interface Command permits Start, WrappedMemoryRecall, WrappedLlmResponse, WrappedToolResult, ToolTimeout {
    }

    public record Start() implements Command {
    }

    private record WrappedMemoryRecall(AgentMemoryRegistryActor.Recalled recalled) implements Command {
    }

    private record WrappedLlmResponse(LlmProtocol.Response response) implements Command {
    }

    private record WrappedToolResult(ToolProtocol.ToolResult result) implements Command {
    }

    private record ToolTimeout(String toolName, int iteration) implements Command {
    }

    public record Result(String agentName, String output, List<String> sources, List<AgentError> errors) {
        public Result {
            sources = sources == null ? List.of() : List.copyOf(sources);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean isSuccess() {
            return errors.isEmpty();
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .onMessage(WrappedMemoryRecall.class, this::onWrappedMemoryRecall)
                .onMessage(WrappedLlmResponse.class, this::onWrappedLlmResponse)
                .onMessage(WrappedToolResult.class, this::onWrappedToolResult)
                .onMessage(ToolTimeout.class, this::onToolTimeout)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        getContext().getLog().info(
                "Agent step started request_id={} tenant={} agent={} task={}",
                request.requestId(),
                request.tenantId(),
                agentLoggerName(agent.name()),
                task.name()
        );
        if (shouldRecall()) {
            ActorRef<AgentMemoryRegistryActor.Recalled> adapter = getContext().messageAdapter(
                    AgentMemoryRegistryActor.Recalled.class,
                    WrappedMemoryRecall::new
            );
            memoryRegistry.tell(new AgentMemoryRegistryActor.Recall(memoryKey(), agent.memory().maxEvents(), adapter));
            return this;
        }
        return requestLlm();
    }

    private Behavior<Command> onWrappedMemoryRecall(WrappedMemoryRecall wrapped) {
        recalledMemory = wrapped.recalled().events();
        return requestLlm();
    }

    private Behavior<Command> requestLlm() {
        if (iteration >= task.maxIterations()) {
            return finishFailure("agent_iterations_exhausted", "Agent exhausted max iterations before producing a final answer.", true);
        }
        ActorRef<LlmProtocol.Response> adapter = getContext().messageAdapter(LlmProtocol.Response.class, WrappedLlmResponse::new);
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":agent:" + agent.name() + ":iteration:" + iteration,
                prompt(),
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedLlmResponse(WrappedLlmResponse wrapped) {
        if (!wrapped.response().isSuccess()) {
            remember(AgentMemoryEventType.USER_TASK, originalInput);
            remember(AgentMemoryEventType.FAILURE, wrapped.response().error().getMessage());
            return finishFailure("agent_llm_failed", wrapped.response().error().getMessage(), true);
        }
        String text = wrapped.response().text();
        Optional<ToolCallParser.ToolCall> toolCall = ToolCallParser.parse(text);
        if (toolCall.isEmpty()) {
            String output = ToolCallParser.finalText(text);
            remember(AgentMemoryEventType.USER_TASK, originalInput);
            remember(AgentMemoryEventType.AGENT_OUTPUT, output);
            replyTo.tell(new Result(agent.name(), output, sources, List.of()));
            return Behaviors.stopped();
        }
        if (iteration + 1 >= task.maxIterations()) {
            remember(AgentMemoryEventType.USER_TASK, originalInput);
            remember(AgentMemoryEventType.FAILURE, "Tool requested after iteration budget was exhausted: " + toolCall.get().toolName());
            return finishFailure(
                    "agent_iterations_exhausted",
                    "Agent requested tool after max iterations: " + toolCall.get().toolName(),
                    true
            );
        }
        if (!declaredTools().contains(toolCall.get().toolName())) {
            String observation = "Tool " + toolCall.get().toolName() + " failed: undeclared for agent " + agent.name();
            observations.add(observation);
            remember(AgentMemoryEventType.TOOL_OBSERVATION, observation);
            iteration++;
            return requestLlm();
        }
        pendingTool = toolCall.get();
        getContext().getLog().info(
                "Agent dispatching tool request_id={} tenant={} agent={} tool={} iteration={}",
                request.requestId(),
                request.tenantId(),
                agentLoggerName(agent.name()),
                pendingTool.toolName(),
                iteration + 1
        );
        ActorRef<ToolProtocol.ToolResult> adapter = getContext().messageAdapter(ToolProtocol.ToolResult.class, WrappedToolResult::new);
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout(pendingTool.toolName(), iteration));
        toolRegistry.tell(new ToolProtocol.InvokeTool(
                request.requestId() + ":agent:" + agent.name() + ":tool:" + iteration + ":" + pendingTool.toolName(),
                request.tenantId(),
                agent.name(),
                pendingTool.toolName(),
                originalInput,
                pendingTool.arguments(),
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedToolResult(WrappedToolResult wrapped) {
        ToolProtocol.ToolResult result = wrapped.result();
        if (pendingTool == null || !pendingTool.toolName().equals(result.toolName())) {
            return this;
        }
        if (result.isSuccess()) {
            getContext().getLog().info(
                    "Agent observed tool result request_id={} tenant={} agent={} tool={} iteration={} output_chars={} sources={}",
                    request.requestId(),
                    request.tenantId(),
                    agentLoggerName(agent.name()),
                    result.toolName(),
                    iteration + 1,
                    result.output().length(),
                    result.sources().size()
            );
            String observation = "Tool " + result.toolName() + ":\n" + result.output();
            observations.add(observation);
            sources.addAll(result.sources());
            remember(AgentMemoryEventType.TOOL_OBSERVATION, observation);
        } else {
            getContext().getLog().warn(
                    "Agent observed tool failure request_id={} tenant={} agent={} tool={} iteration={} error={}",
                    request.requestId(),
                    request.tenantId(),
                    agentLoggerName(agent.name()),
                    result.toolName(),
                    iteration + 1,
                    result.error().toString()
            );
            String observation = "Tool " + result.toolName() + " failed: " + result.error().getMessage();
            observations.add(observation);
            remember(AgentMemoryEventType.TOOL_OBSERVATION, observation);
        }
        pendingTool = null;
        iteration++;
        return requestLlm();
    }

    private Behavior<Command> onToolTimeout(ToolTimeout timeout) {
        if (pendingTool == null || timeout.iteration() != iteration || !pendingTool.toolName().equals(timeout.toolName())) {
            return this;
        }
        getContext().getLog().warn(
                "Agent tool timed out request_id={} tenant={} agent={} tool={} iteration={}",
                request.requestId(),
                request.tenantId(),
                agentLoggerName(agent.name()),
                timeout.toolName(),
                iteration + 1
        );
        String observation = "Tool " + timeout.toolName() + " timed out.";
        observations.add(observation);
        remember(AgentMemoryEventType.TOOL_OBSERVATION, observation);
        pendingTool = null;
        iteration++;
        return requestLlm();
    }

    private Behavior<Command> finishFailure(String code, String message, boolean retryable) {
        replyTo.tell(new Result(agent.name(), "", sources, List.of(new AgentError(code, message, retryable, agent.name()))));
        return Behaviors.stopped();
    }

    private String prompt() {
        return """
                You are agent "%s".
                Instructions:
                %s

                Original user task:
                %s

                Current input for this agent:
                %s

                Task definition:
                - Name: %s
                - Description: %s

                Context:
                - Memory from previous events:
                %s

                - Retrieved knowledge:
                %s

                - Tool observations from this agent:
                %s

                Available tools:
                %s

                If you need a tool, respond exactly:
                TOOL: tool.name
                ARG key=value

                If you can answer, respond with:
                FINAL: your answer
                """.formatted(
                safe(agent.name()),
                safe(agent.instructions()),
                safe(originalInput),
                safe(stepInput),
                safe(task.name()),
                safe(task.description()),
                memorySection(),
                retrievedKnowledgeContext,
                observations.isEmpty() ? "None." : String.join("\n\n", observations),
                toolsSection()
        );
    }

    private boolean shouldRecall() {
        return agent.memory() != null && agent.memory().enabled();
    }

    private void remember(AgentMemoryEventType type, String content) {
        if (!shouldRemember(agent.memory(), type) || content == null || content.isBlank()) {
            return;
        }
        memoryRegistry.tell(new AgentMemoryRegistryActor.Append(memoryKey(), type, request.requestId(), content, agent.memory().maxEvents()));
    }

    private AgentMemoryKey memoryKey() {
        return new AgentMemoryKey(request.tenantId(), gatewayName, agent.name());
    }

    private String memorySection() {
        if (recalledMemory.isEmpty()) {
            return "None.";
        }
        return recalledMemory.stream()
                .map(event -> "- [%s] %s".formatted(event.type(), truncate(event.content())))
                .collect(Collectors.joining("\n"));
    }

    private Set<String> declaredTools() {
        return new LinkedHashSet<>(agent.tools());
    }

    private String toolsSection() {
        if (agent.tools().isEmpty()) {
            return "None.";
        }
        Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
        for (AgentToolDefinition definition : toolDefinitions) {
            definitions.putIfAbsent(definition.name(), definition);
        }
        return agent.tools().stream()
                .map(tool -> {
                    AgentToolDefinition definition = definitions.get(tool);
                    String description = definition == null ? "" : definition.description();
                    return "- " + tool + (description == null || description.isBlank() ? "" : ": " + description);
                })
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= 1200 ? stripped : stripped.substring(0, 1200) + "...";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "None." : value;
    }

    private static String agentLoggerName(String agentName) {
        return agentName == null || agentName.isBlank() ? "agent.<unknown>" : "agent." + agentName;
    }

    private static boolean shouldRemember(AgentMemoryConfig memory, AgentMemoryEventType type) {
        if (memory == null) {
            return false;
        }
        return memory.shouldRemember(switch (type) {
            case USER_TASK -> AgentMemoryConfig.MemoryEventType.USER_TASK;
            case TOOL_OBSERVATION -> AgentMemoryConfig.MemoryEventType.TOOL_OBSERVATION;
            case AGENT_OUTPUT -> AgentMemoryConfig.MemoryEventType.AGENT_OUTPUT;
            case FINAL_ANSWER -> AgentMemoryConfig.MemoryEventType.FINAL_ANSWER;
            case FAILURE -> AgentMemoryConfig.MemoryEventType.FAILURE;
        });
    }
}
