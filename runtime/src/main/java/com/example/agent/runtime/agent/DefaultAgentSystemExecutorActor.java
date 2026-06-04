package com.example.agent.runtime.agent;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.rag.core.RagContextBuilder;
import com.example.agent.rag.core.RagRetrievalResult;
import com.example.agent.rag.core.RagSecurityContext;
import com.example.agent.rag.runtime.RagProtocol;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentStatus;
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
import java.util.Set;
import java.util.stream.Collectors;

public final class DefaultAgentSystemExecutorActor extends AbstractBehavior<DefaultAgentSystemExecutorActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry;
    private final ActorRef<RagProtocol.Command> ragRuntime;
    private final boolean ragEnabled;
    private final int ragTopK;
    private final RagContextBuilder ragContextBuilder;
    private final Duration executionTimeout;
    private final Duration toolTimeout;
    private final List<String> observations = new ArrayList<>();
    private final Map<String, String> delegateOutputs = new LinkedHashMap<>();
    private final Map<String, List<AgentMemoryEvent>> recalledMemory = new LinkedHashMap<>();
    private final List<String> sources = new ArrayList<>();
    private String retrievedKnowledgeContext = "None.";
    private boolean ragAttempted;
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
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry,
            ActorRef<RagProtocol.Command> ragRuntime,
            boolean ragEnabled,
            int ragTopK,
            int ragMaxContextChars,
            Duration executionTimeout,
            Duration toolTimeout
    ) {
        return Behaviors.setup(context -> new DefaultAgentSystemExecutorActor(
                context,
                llmWorker,
                toolRegistry,
                memoryRegistry,
                ragRuntime,
                ragEnabled,
                ragTopK,
                ragMaxContextChars,
                executionTimeout,
                toolTimeout
        ));
    }

    private DefaultAgentSystemExecutorActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry,
            ActorRef<RagProtocol.Command> ragRuntime,
            boolean ragEnabled,
            int ragTopK,
            int ragMaxContextChars,
            Duration executionTimeout,
            Duration toolTimeout
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.memoryRegistry = Objects.requireNonNull(memoryRegistry);
        this.ragRuntime = Objects.requireNonNull(ragRuntime);
        this.ragEnabled = ragEnabled;
        this.ragTopK = Math.max(1, ragTopK);
        this.ragContextBuilder = new RagContextBuilder(ragMaxContextChars);
        this.executionTimeout = Objects.requireNonNull(executionTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
    }

    public sealed interface Command permits Start, ExecutionTimeout, ToolTimeout, WrappedToolResult, WrappedRagResult, WrappedMemoryRecall, WrappedDelegateResponse, WrappedGatewayResponse {
    }

    public record Start(AgentRequest request, AgentSystemDefinition system, ActorRef<AgentResult> replyTo) implements Command {
    }

    private record ExecutionTimeout() implements Command {
    }

    private record ToolTimeout(String toolName) implements Command {
    }

    private record WrappedToolResult(ToolProtocol.ToolResult result) implements Command {
    }

    private record WrappedRagResult(RagProtocol.Retrieved retrieved) implements Command {
    }

    private record WrappedMemoryRecall(String agentName, AgentMemoryRegistryActor.Recalled recalled) implements Command {
    }

    private record WrappedDelegateResponse(String agentName, LlmProtocol.Response response) implements Command {
    }

    private record WrappedGatewayResponse(LlmProtocol.Response response) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .onMessage(ExecutionTimeout.class, this::onExecutionTimeout)
                .onMessage(ToolTimeout.class, this::onToolTimeout)
                .onMessage(WrappedToolResult.class, this::onWrappedToolResult)
                .onMessage(WrappedRagResult.class, this::onWrappedRagResult)
                .onMessage(WrappedMemoryRecall.class, this::onWrappedMemoryRecall)
                .onMessage(WrappedDelegateResponse.class, this::onWrappedDelegateResponse)
                .onMessage(WrappedGatewayResponse.class, this::onWrappedGatewayResponse)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        request = start.request();
        system = start.system();
        replyTo = start.replyTo();
        getContext().scheduleOnce(executionTimeout, getContext().getSelf(), new ExecutionTimeout());
        getContext().getLog().info(
                "Agent system accepted request {} gateway={} delegates={}",
                request.requestId(),
                system.entrypoint().name(),
                system.entrypoint().delegates()
        );
        pendingTools = toolsFor(system);
        delegates = delegatesFor(system);
        return requestNextToolOrRag();
    }

    private Behavior<Command> requestNextToolOrRag() {
        if (pendingToolIndex >= pendingTools.size()) {
            return requestRagOrDelegation();
        }
        String toolName = pendingTools.get(pendingToolIndex);
        ActorRef<ToolProtocol.ToolResult> adapter = getContext().messageAdapter(ToolProtocol.ToolResult.class, WrappedToolResult::new);
        getContext().scheduleOnce(toolTimeout, getContext().getSelf(), new ToolTimeout(toolName));
        toolRegistry.tell(new ToolProtocol.InvokeTool(
                request.requestId() + ":agent-system:tool:" + pendingToolIndex + ":" + toolName,
                request.tenantId(),
                toolName,
                request.input(),
                Map.of(),
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedToolResult(WrappedToolResult wrapped) {
        ToolProtocol.ToolResult result = wrapped.result();
        if (result.isSuccess()) {
            observations.add("Tool " + result.toolName() + ":\n" + result.output());
            sources.addAll(result.sources());
        } else {
            observations.add("Tool " + result.toolName() + " failed: " + result.error().getMessage());
        }
        pendingToolIndex++;
        return requestNextToolOrRag();
    }

    private Behavior<Command> onToolTimeout(ToolTimeout timeout) {
        if (pendingToolIndex < pendingTools.size() && pendingTools.get(pendingToolIndex).equals(timeout.toolName())) {
            observations.add("Tool " + timeout.toolName() + " timed out.");
            pendingToolIndex++;
            return requestNextToolOrRag();
        }
        return this;
    }

    private Behavior<Command> requestRagOrDelegation() {
        if (!ragEnabled || ragAttempted) {
            return requestNextDelegateOrSynthesis();
        }
        ragAttempted = true;
        ActorRef<RagProtocol.Retrieved> adapter = getContext().messageAdapter(RagProtocol.Retrieved.class, WrappedRagResult::new);
        ragRuntime.tell(new RagProtocol.Retrieve(
                request.requestId() + ":rag",
                request.input(),
                new RagSecurityContext(request.tenantId(), "", Map.of()),
                ragTopK,
                adapter
        ));
        return this;
    }

    private Behavior<Command> onWrappedRagResult(WrappedRagResult wrapped) {
        if (!wrapped.retrieved().isSuccess()) {
            getContext().getLog().warn(
                    "RAG retrieval failed request_id={} tenant={} error={}",
                    request.requestId(),
                    request.tenantId(),
                    wrapped.retrieved().failure().toString()
            );
            retrievedKnowledgeContext = "None.";
            return requestNextDelegateOrSynthesis();
        }
        RagRetrievalResult result = wrapped.retrieved().result() == null
                ? RagRetrievalResult.empty()
                : wrapped.retrieved().result();
        retrievedKnowledgeContext = ragContextBuilder.build(result);
        sources.addAll(result.chunks().stream()
                .map(chunk -> chunk.citation())
                .filter(citation -> citation != null && !citation.isBlank())
                .toList());
        getContext().getLog().info(
                "RAG retrieval completed request_id={} tenant={} chunks={}",
                request.requestId(),
                request.tenantId(),
                result.chunks().size()
        );
        return requestNextDelegateOrSynthesis();
    }

    private Behavior<Command> requestNextDelegateOrSynthesis() {
        int maxIterations = system.entrypoint().acceptedTask() == null
                ? 4
                : system.entrypoint().acceptedTask().resolvedMaxIterations();
        if (pendingDelegateIndex >= delegates.size() || pendingDelegateIndex >= maxIterations) {
            return requestGatewaySynthesis();
        }
        AgentDefinition delegate = delegates.get(pendingDelegateIndex);
        if (shouldRecall(delegate.name(), delegate.memory())) {
            recallMemory(delegate.name(), delegate.memory());
            return this;
        }
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

    private Behavior<Command> onWrappedMemoryRecall(WrappedMemoryRecall wrapped) {
        recalledMemory.put(wrapped.agentName(), wrapped.recalled().events());
        return requestNextToolOrRag();
    }

    private Behavior<Command> onWrappedDelegateResponse(WrappedDelegateResponse wrapped) {
        AgentDefinition delegate = currentDelegate(wrapped.agentName());
        if (wrapped.response().isSuccess()) {
            delegateOutputs.put(wrapped.agentName(), wrapped.response().text());
            if (delegate != null) {
                remember(delegate.name(), delegate.memory(), AgentMemoryEventType.USER_TASK, request.input());
                remember(delegate.name(), delegate.memory(), AgentMemoryEventType.AGENT_OUTPUT, wrapped.response().text());
            }
        } else {
            observations.add("Agent " + wrapped.agentName() + " failed: " + wrapped.response().error().getMessage());
            if (delegate != null) {
                remember(delegate.name(), delegate.memory(), AgentMemoryEventType.USER_TASK, request.input());
                remember(delegate.name(), delegate.memory(), AgentMemoryEventType.FAILURE, wrapped.response().error().getMessage());
            }
        }
        pendingDelegateIndex++;
        return requestNextDelegateOrSynthesis();
    }

    private Behavior<Command> requestGatewaySynthesis() {
        GatewayAgentDefinition gateway = system.entrypoint();
        if (shouldRecall(gateway.name(), gateway.memory())) {
            recallMemory(gateway.name(), gateway.memory());
            return this;
        }
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
            rememberGateway(AgentMemoryEventType.USER_TASK, request.input());
            rememberObservationsForGateway();
            rememberGateway(AgentMemoryEventType.FAILURE, wrapped.response().error().getMessage());
            replyTo.tell(new AgentResult(
                    request.requestId(),
                    AgentStatus.FAILED_SYSTEM,
                    "",
                    sources.stream().distinct().toList(),
                    List.of(new AgentError("gateway_synthesis_failed", wrapped.response().error().getMessage(), false, "agent-system"))
            ));
            return Behaviors.stopped();
        }
        rememberGateway(AgentMemoryEventType.USER_TASK, request.input());
        rememberObservationsForGateway();
        rememberGateway(AgentMemoryEventType.FINAL_ANSWER, wrapped.response().text());
        replyTo.tell(new AgentResult(
                request.requestId(),
                AgentStatus.COMPLETED,
                withSources(wrapped.response().text()),
                sources.stream().distinct().toList(),
                List.of()
        ));
        return Behaviors.stopped();
    }

    private Behavior<Command> onExecutionTimeout(ExecutionTimeout timeout) {
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

                Context:
                - Memory from previous events:
                %s

                - Retrieved knowledge:
                %s

                - Shared observations from this run:
                %s

                Return only your contribution. Be concise and factual.
                """.formatted(
                safe(delegate.name()),
                safe(delegate.instructions()),
                request.input(),
                memorySection(delegate.name()),
                retrievedKnowledgeContext,
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

                Context:
                - Memory from previous events:
                %s

                - Retrieved knowledge:
                %s

                - Tool observations from this run:
                %s

                - Delegated agent outputs from this run:
                %s

                Produce the final answer for the user.
                """.formatted(
                safe(system.entrypoint().name()),
                safe(system.entrypoint().instructions()),
                request.input(),
                memorySection(system.entrypoint().name()),
                retrievedKnowledgeContext,
                observations.isEmpty() ? "None." : String.join("\n\n", observations),
                delegateContext
        );
    }

    private boolean shouldRecall(String agentName, MemoryDefinition memory) {
        return memory != null && memory.enabled() && !recalledMemory.containsKey(agentName);
    }

    private void recallMemory(String agentName, MemoryDefinition memory) {
        ActorRef<AgentMemoryRegistryActor.Recalled> adapter = getContext().messageAdapter(
                AgentMemoryRegistryActor.Recalled.class,
                recalled -> new WrappedMemoryRecall(agentName, recalled)
        );
        memoryRegistry.tell(new AgentMemoryRegistryActor.Recall(memoryKey(agentName), memory.maxEvents(), adapter));
    }

    private void rememberGateway(AgentMemoryEventType type, String content) {
        GatewayAgentDefinition gateway = system.entrypoint();
        remember(gateway.name(), gateway.memory(), type, content);
    }

    private void rememberObservationsForGateway() {
        for (String observation : observations) {
            rememberGateway(AgentMemoryEventType.TOOL_OBSERVATION, observation);
        }
    }

    private void remember(String agentName, MemoryDefinition memory, AgentMemoryEventType type, String content) {
        if (memory == null || !memory.shouldRemember(type) || content == null || content.isBlank()) {
            return;
        }
        memoryRegistry.tell(new AgentMemoryRegistryActor.Append(memoryKey(agentName), type, request.requestId(), content, memory.maxEvents()));
    }

    private AgentDefinition currentDelegate(String agentName) {
        return delegates.stream()
                .filter(delegate -> delegate.name().equals(agentName))
                .findFirst()
                .orElse(null);
    }

    private AgentMemoryKey memoryKey(String agentName) {
        return new AgentMemoryKey(request.tenantId(), system.entrypoint().name(), agentName);
    }

    private String memorySection(String agentName) {
        List<AgentMemoryEvent> events = recalledMemory.getOrDefault(agentName, List.of());
        if (events.isEmpty()) {
            return "None.";
        }
        return events.stream()
                .map(event -> "- [%s] %s".formatted(event.type(), truncate(event.content())))
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= 1200 ? stripped : stripped.substring(0, 1200) + "...";
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
        return List.copyOf(tools);
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
