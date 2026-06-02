package com.example.agent.runtime.agent;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentStatus;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentSystemActor extends AbstractBehavior<AgentSystemActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final Duration workflowTimeout;
    private final Duration toolTimeout;
    private final List<String> observations = new ArrayList<>();
    private final Map<String, String> delegateOutputs = new LinkedHashMap<>();
    private final List<String> sources = new ArrayList<>();
    private AgentRequest request;
    private AgentSystemDefinition system;
    private ActorRef<AgentResult> replyTo;
    private List<String> pendingTools = List.of();
    private int pendingToolIndex;
    private List<AgentDefinition> delegates = List.of();
    private int pendingDelegateIndex;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            Duration workflowTimeout,
            Duration toolTimeout
    ) {
        return Behaviors.setup(context -> new AgentSystemActor(context, llmWorker, toolRegistry, workflowTimeout, toolTimeout));
    }

    private AgentSystemActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            Duration workflowTimeout,
            Duration toolTimeout
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.workflowTimeout = Objects.requireNonNull(workflowTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
    }

    public sealed interface Command permits Start, WorkflowTimeout, ToolTimeout, WrappedToolResult, WrappedDelegateResponse, WrappedGatewayResponse {
    }

    public record Start(AgentRequest request, AgentSystemDefinition system, ActorRef<AgentResult> replyTo) implements Command {
    }

    private record WorkflowTimeout() implements Command {
    }

    private record ToolTimeout(String toolName) implements Command {
    }

    private record WrappedToolResult(ToolProtocol.ToolResult result) implements Command {
    }

    private record WrappedDelegateResponse(String agentName, LlmProtocol.Response response) implements Command {
    }

    private record WrappedGatewayResponse(LlmProtocol.Response response) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .onMessage(WorkflowTimeout.class, this::onWorkflowTimeout)
                .onMessage(ToolTimeout.class, this::onToolTimeout)
                .onMessage(WrappedToolResult.class, this::onWrappedToolResult)
                .onMessage(WrappedDelegateResponse.class, this::onWrappedDelegateResponse)
                .onMessage(WrappedGatewayResponse.class, this::onWrappedGatewayResponse)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        request = start.request();
        system = start.system();
        replyTo = start.replyTo();
        getContext().scheduleOnce(workflowTimeout, getContext().getSelf(), new WorkflowTimeout());
        getContext().getLog().info(
                "Agent system accepted request {} gateway={} delegates={}",
                request.requestId(),
                system.entrypoint().name(),
                system.entrypoint().delegates()
        );
        pendingTools = toolsFor(system);
        delegates = delegatesFor(system);
        return requestNextToolOrDelegation();
    }

    private Behavior<Command> requestNextToolOrDelegation() {
        if (pendingToolIndex >= pendingTools.size()) {
            return requestNextDelegateOrSynthesis();
        }
        String toolName = pendingTools.get(pendingToolIndex);
        ActorRef<ToolProtocol.ToolResult> adapter = getContext().messageAdapter(ToolProtocol.ToolResult.class, WrappedToolResult::new);
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout(toolName));
        toolRegistry.tell(new ToolProtocol.InvokeTool(
                request.requestId() + ":agent-system:tool:" + pendingToolIndex + ":" + toolName,
                toolName,
                ToolCatalog.defaultArguments(toolName, request.input(), null),
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedToolResult(WrappedToolResult wrapped) {
        ToolProtocol.ToolResult result = wrapped.result();
        if (result.isSuccess()) {
            observations.add("Tool " + result.toolName() + ":\n" + result.output());
            sources.addAll(sourceUrls(result.output()));
        } else {
            observations.add("Tool " + result.toolName() + " failed: " + result.error().getMessage());
        }
        pendingToolIndex++;
        return requestNextToolOrDelegation();
    }

    private Behavior<Command> onToolTimeout(ToolTimeout timeout) {
        if (pendingToolIndex < pendingTools.size() && pendingTools.get(pendingToolIndex).equals(timeout.toolName())) {
            observations.add("Tool " + timeout.toolName() + " timed out.");
            pendingToolIndex++;
            return requestNextToolOrDelegation();
        }
        return this;
    }

    private Behavior<Command> requestNextDelegateOrSynthesis() {
        int maxIterations = system.entrypoint().acceptedTask() == null
                ? 4
                : system.entrypoint().acceptedTask().resolvedMaxIterations();
        if (pendingDelegateIndex >= delegates.size() || pendingDelegateIndex >= maxIterations) {
            return requestGatewaySynthesis();
        }
        AgentDefinition delegate = delegates.get(pendingDelegateIndex);
        ActorRef<LlmProtocol.Response> adapter = getContext().messageAdapter(
                LlmProtocol.Response.class,
                response -> new WrappedDelegateResponse(delegate.name(), response)
        );
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":agent-system:delegate:" + delegate.name(),
                delegatePrompt(delegate),
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedDelegateResponse(WrappedDelegateResponse wrapped) {
        if (wrapped.response().isSuccess()) {
            delegateOutputs.put(wrapped.agentName(), wrapped.response().text());
        } else {
            observations.add("Agent " + wrapped.agentName() + " failed: " + wrapped.response().error().getMessage());
        }
        pendingDelegateIndex++;
        return requestNextDelegateOrSynthesis();
    }

    private Behavior<Command> requestGatewaySynthesis() {
        ActorRef<LlmProtocol.Response> adapter = getContext().messageAdapter(LlmProtocol.Response.class, WrappedGatewayResponse::new);
        llmWorker.tell(new LlmProtocol.Ask(
                request.requestId() + ":agent-system:gateway:" + system.entrypoint().name(),
                gatewayPrompt(),
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedGatewayResponse(WrappedGatewayResponse wrapped) {
        if (!wrapped.response().isSuccess()) {
            replyTo.tell(new AgentResult(
                    request.requestId(),
                    AgentStatus.FAILED_SYSTEM,
                    "",
                    sources.stream().distinct().toList(),
                    List.of(new AgentError("gateway_synthesis_failed", wrapped.response().error().getMessage(), false, "agent-system"))
            ));
            return Behaviors.stopped();
        }
        replyTo.tell(new AgentResult(
                request.requestId(),
                AgentStatus.COMPLETED,
                withSources(wrapped.response().text()),
                sources.stream().distinct().toList(),
                List.of()
        ));
        return Behaviors.stopped();
    }

    private Behavior<Command> onWorkflowTimeout(WorkflowTimeout timeout) {
        if (replyTo != null && request != null) {
            replyTo.tell(new AgentResult(
                    request.requestId(),
                    AgentStatus.TIMEOUT,
                    "",
                    sources.stream().distinct().toList(),
                    List.of(new AgentError("agent_system_timeout", "Agent system execution timed out.", true, "agent-system"))
            ));
        }
        return Behaviors.stopped();
    }

    private String delegatePrompt(AgentDefinition delegate) {
        return """
                You are agent "%s".
                Instructions:
                %s

                User task:
                %s

                Shared observations:
                %s

                Return only your contribution. Be concise and factual.
                """.formatted(
                safe(delegate.name()),
                safe(delegate.instructions()),
                request.input(),
                observations.isEmpty() ? "None." : String.join("\n\n", observations)
        );
    }

    private String gatewayPrompt() {
        String delegateContext = delegateOutputs.isEmpty()
                ? "No delegated agent outputs."
                : delegateOutputs.entrySet().stream()
                .map(entry -> "Agent " + entry.getKey() + ":\n" + entry.getValue())
                .collect(Collectors.joining("\n\n"));
        return """
                You are gateway agent "%s".
                Instructions:
                %s

                User task:
                %s

                Tool observations:
                %s

                Delegated agent outputs:
                %s

                Produce the final answer for the user.
                """.formatted(
                safe(system.entrypoint().name()),
                safe(system.entrypoint().instructions()),
                request.input(),
                observations.isEmpty() ? "None." : String.join("\n\n", observations),
                delegateContext
        );
    }

    private static List<String> toolsFor(AgentSystemDefinition system) {
        Set<String> tools = new LinkedHashSet<>(system.entrypoint().tools());
        Map<String, AgentDefinition> agents = system.agents().stream()
                .collect(Collectors.toMap(AgentDefinition::name, agent -> agent, (first, second) -> first));
        for (String delegate : system.entrypoint().delegates()) {
            AgentDefinition agent = agents.get(delegate);
            if (agent != null) {
                tools.addAll(agent.tools());
            }
        }
        return tools.stream().filter(ToolCatalog::isKnownTool).toList();
    }

    private static List<AgentDefinition> delegatesFor(AgentSystemDefinition system) {
        Map<String, AgentDefinition> agents = system.agents().stream()
                .collect(Collectors.toMap(AgentDefinition::name, agent -> agent, (first, second) -> first));
        List<AgentDefinition> selected = new ArrayList<>();
        for (String delegate : system.entrypoint().delegates()) {
            AgentDefinition agent = agents.get(delegate);
            if (agent != null) {
                selected.add(agent);
            }
        }
        return selected;
    }

    private String withSources(String answer) {
        if (sources.isEmpty() || sources.stream().anyMatch(answer::contains)) {
            return answer;
        }
        return answer.stripTrailing() + "\n\nSources:\n" + sources.stream()
                .distinct()
                .map(source -> "- " + source)
                .collect(Collectors.joining("\n"));
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

    private static String safe(String value) {
        return value == null || value.isBlank() ? "None." : value;
    }
}
