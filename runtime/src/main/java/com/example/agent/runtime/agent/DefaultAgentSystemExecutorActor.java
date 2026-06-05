package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTaskDefinition;
import com.example.agent.api.GatewayAgent;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public sealed interface Command permits Start, ExecutionTimeout, WrappedToolsRegistered, WrappedRagResult, WrappedStepResult, WrappedGatewayMemoryRecall, WrappedGatewayResponse {
    }

    public record Start(AgentRequest request, AgentSystem system, ActorRef<AgentResult> replyTo) implements Command {
    }

    private record ExecutionTimeout() implements Command {
    }

    private record WrappedToolsRegistered(ToolProtocol.ToolsRegistered registered) implements Command {
    }

    private record WrappedRagResult(RagProtocol.Retrieved retrieved) implements Command {
    }

    private record WrappedStepResult(AgentStepActor.Result result) implements Command {
    }

    private record WrappedGatewayMemoryRecall(AgentMemoryRegistryActor.Recalled recalled) implements Command {
    }

    private record WrappedGatewayResponse(LlmProtocol.Response response) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Start.class, this::onStart)
                .build();
    }

    private Behavior<Command> onStart(Start start) {
        ExecutionState state = ExecutionState.initial(start.request(), start.system(), start.replyTo());
        getContext().scheduleOnce(executionTimeout, getContext().getSelf(), new ExecutionTimeout());
        getContext().getLog().info(
                "Agent system accepted request={} gateway={} delegates={}",
                state.request().requestId(), state.system().entrypoint().name(), state.system().entrypoint().delegates());

        var toolDefs = state.system().toolDefinitions();
        if (!toolDefs.isEmpty()) {
            ActorRef<ToolProtocol.ToolsRegistered> adapter =
                    getContext().messageAdapter(ToolProtocol.ToolsRegistered.class, WrappedToolsRegistered::new);
            toolRegistry.tell(new ToolProtocol.RegisterTools(
                    state.request().requestId() + ":register",
                    state.request().tenantId(),
                    toolDefs,
                    adapter));
            return waitingForToolRegistration(state);
        }
        return afterRegistration(state);
    }

    private Behavior<Command> waitingForToolRegistration(ExecutionState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedToolsRegistered.class, msg -> onToolsRegistered(state, msg))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onToolsRegistered(ExecutionState state, WrappedToolsRegistered msg) {
        getContext().getLog().debug(
                "Tools registered request={} count={}",
                state.request().requestId(), msg.registered().registeredCount());
        return afterRegistration(state);
    }

    private Behavior<Command> afterRegistration(ExecutionState state) {
        if (ragEnabled) {
            ActorRef<RagProtocol.Retrieved> adapter =
                    getContext().messageAdapter(RagProtocol.Retrieved.class, WrappedRagResult::new);
            ragRuntime.tell(new RagProtocol.Retrieve(
                    state.request().requestId() + ":rag",
                    state.request().input(),
                    new RagSecurityContext(state.request().tenantId(), "", Map.of()),
                    ragTopK,
                    adapter));
            return waitingForRag(state);
        }
        return runNextDelegate(state);
    }

    private Behavior<Command> waitingForRag(ExecutionState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedRagResult.class, wrapped -> onRagResult(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onRagResult(ExecutionState state, WrappedRagResult wrapped) {
        ExecutionState next = state;
        if (wrapped.retrieved().isSuccess() && wrapped.retrieved().result() != null) {
            RagRetrievalResult result = wrapped.retrieved().result();
            String context = ragContextBuilder.build(result);
            List<String> citations = result.chunks().stream()
                    .map(chunk -> chunk.citation())
                    .filter(citation -> citation != null && !citation.isBlank())
                    .toList();
            next = state.withRag(context, citations);
            getContext().getLog().info("RAG completed request={} chunks={}",
                    state.request().requestId(), result.chunks().size());
        } else {
            getContext().getLog().warn("RAG failed or empty request={}", state.request().requestId());
        }
        return runNextDelegate(next);
    }

    private Behavior<Command> runNextDelegate(ExecutionState state) {
        if (state.delegateIndex() >= state.delegates().size()) {
            return runGatewaySynthesis(state);
        }
        Agent delegate = state.delegates().get(state.delegateIndex());
        ActorRef<AgentStepActor.Result> stepReply =
                getContext().messageAdapter(AgentStepActor.Result.class, WrappedStepResult::new);
        ActorRef<AgentStepActor.Command> stepActor = getContext().spawn(
                AgentStepActor.create(
                        llmWorker,
                        toolRegistry,
                        memoryRegistry,
                        state.request(),
                        state.system().entrypoint().name(),
                        delegate,
                        taskFor(state.system(), delegate),
                        state.request().input(),
                        state.lastStepOutput(),
                        state.ragContext(),
                        state.system().toolDefinitions(),
                        toolTimeout,
                        stepReply),
                "step-" + delegate.name() + "-" + state.request().requestId());
        getContext().getLog().info(
                "Spawning step actor request={} agent={} hasPrior={}",
                state.request().requestId(), delegate.name(), !state.lastStepOutput().isEmpty());
        stepActor.tell(new AgentStepActor.Start());
        return runningDelegate(state);
    }

    private Behavior<Command> runningDelegate(ExecutionState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedStepResult.class, wrapped -> onStepResult(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onStepResult(ExecutionState state, WrappedStepResult wrapped) {
        AgentStepActor.Result result = wrapped.result();
        ExecutionState next = state.advanceDelegate();
        if (result.isSuccess()) {
            next = next.withStepResult(result);
            getContext().getLog().info(
                    "Step completed request={} agent={} output_chars={} sources={}",
                    state.request().requestId(),
                    result.agentName(),
                    result.output().length(),
                    result.sources().size());
        } else {
            String err = result.errors().isEmpty() ? "unknown" : result.errors().getFirst().message();
            getContext().getLog().warn(
                    "Step failed request={} agent={} error={}",
                    state.request().requestId(), result.agentName(), err);
        }
        return runNextDelegate(next);
    }

    private Behavior<Command> runGatewaySynthesis(ExecutionState state) {
        GatewayAgent gateway = state.system().entrypoint();
        if (gateway.memory() != null && gateway.memory().enabled()) {
            ActorRef<AgentMemoryRegistryActor.Recalled> adapter =
                    getContext().messageAdapter(AgentMemoryRegistryActor.Recalled.class, WrappedGatewayMemoryRecall::new);
            memoryRegistry.tell(new AgentMemoryRegistryActor.Recall(
                    new AgentMemoryKey(state.request().tenantId(), gateway.name(), gateway.name()),
                    gateway.memory().maxEvents(),
                    adapter));
            return waitingForGatewayMemory(state);
        }
        return askGatewayLlm(state);
    }

    private Behavior<Command> waitingForGatewayMemory(ExecutionState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedGatewayMemoryRecall.class, wrapped -> onGatewayMemoryRecall(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onGatewayMemoryRecall(ExecutionState state, WrappedGatewayMemoryRecall msg) {
        List<AgentMemoryEvent> memory = msg.recalled().events() == null ? List.of() : msg.recalled().events();
        return askGatewayLlm(state.withGatewayMemory(memory));
    }

    private Behavior<Command> askGatewayLlm(ExecutionState state) {
        ActorRef<LlmProtocol.Response> adapter =
                getContext().messageAdapter(LlmProtocol.Response.class, WrappedGatewayResponse::new);
        llmWorker.tell(new LlmProtocol.Ask(
                state.request().requestId() + ":gateway:" + state.system().entrypoint().name(),
                GatewayPromptBuilder.build(
                        state.request(),
                        state.system().entrypoint(),
                        state.delegateOutputs(),
                        state.gatewayMemory(),
                        state.ragContext()),
                adapter));
        return waitingForGatewayLlm(state);
    }

    private Behavior<Command> waitingForGatewayLlm(ExecutionState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedGatewayResponse.class, wrapped -> onGatewayResponse(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onGatewayResponse(ExecutionState state, WrappedGatewayResponse wrapped) {
        if (!wrapped.response().isSuccess()) {
            String err = wrapped.response().error() != null
                    ? wrapped.response().error().getMessage() : "gateway LLM failure";
            rememberGateway(state, AgentMemoryEventType.FAILURE, err);
            state.replyTo().tell(new AgentResult(
                    state.request().requestId(),
                    AgentStatus.FAILED_SYSTEM,
                    "",
                    state.distinctSources(),
                    List.of(new AgentError("gateway_synthesis_failed", err, false, "gateway"))));
            return Behaviors.stopped();
        }

        String answer = wrapped.response().text();
        rememberGateway(state, AgentMemoryEventType.USER_TASK, state.request().input());
        rememberGateway(state, AgentMemoryEventType.FINAL_ANSWER, answer);
        state.replyTo().tell(new AgentResult(
                state.request().requestId(),
                AgentStatus.COMPLETED,
                withSources(state, answer),
                state.distinctSources(),
                List.of()));
        return Behaviors.stopped();
    }

    private Behavior<Command> onExecutionTimeout(ExecutionState state) {
        state.replyTo().tell(new AgentResult(
                state.request().requestId(),
                AgentStatus.TIMEOUT,
                "",
                state.distinctSources(),
                List.of(new AgentError("executor_timeout", "Agent system execution timed out.", true, "executor"))));
        return Behaviors.stopped();
    }

    private void rememberGateway(ExecutionState state, AgentMemoryEventType type, String content) {
        GatewayAgent gateway = state.system().entrypoint();
        if (!shouldRemember(gateway.memory(), type) || content == null || content.isBlank()) {
            return;
        }
        memoryRegistry.tell(new AgentMemoryRegistryActor.Append(
                new AgentMemoryKey(state.request().tenantId(), gateway.name(), gateway.name()),
                type,
                state.request().requestId(),
                content,
                gateway.memory().maxEvents()));
    }

    private static String withSources(ExecutionState state, String answer) {
        List<String> distinct = state.distinctSources();
        if (distinct.isEmpty() || distinct.stream().anyMatch(answer::contains)) {
            return answer;
        }
        return answer.stripTrailing() + "\n\nSources:\n"
                + distinct.stream().map(source -> "- " + source).collect(Collectors.joining("\n"));
    }

    private static AgentTaskDefinition taskFor(AgentSystem system, Agent delegate) {
        return delegate.acceptedTasks().isEmpty()
                ? system.entrypoint().acceptedTask()
                : delegate.acceptedTasks().getFirst();
    }

    private static List<Agent> delegatesFor(AgentSystem system) {
        Map<String, Agent> byName = system.agents().stream()
                .collect(Collectors.toMap(Agent::name, agent -> agent, (left, right) -> left));
        return system.entrypoint().delegates().stream()
                .map(byName::get)
                .filter(Objects::nonNull)
                .toList();
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

    private record ExecutionState(
            AgentRequest request,
            AgentSystem system,
            ActorRef<AgentResult> replyTo,
            List<Agent> delegates,
            int delegateIndex,
            String ragContext,
            String lastStepOutput,
            Map<String, String> delegateOutputs,
            List<String> allSources,
            List<AgentMemoryEvent> gatewayMemory
    ) {
        private ExecutionState {
            request = Objects.requireNonNull(request);
            system = Objects.requireNonNull(system);
            replyTo = Objects.requireNonNull(replyTo);
            delegates = delegates == null ? List.of() : List.copyOf(delegates);
            delegateIndex = Math.max(0, delegateIndex);
            ragContext = ragContext == null || ragContext.isBlank() ? "None." : ragContext;
            lastStepOutput = lastStepOutput == null ? "" : lastStepOutput;
            delegateOutputs = immutableLinkedMap(delegateOutputs);
            allSources = allSources == null ? List.of() : List.copyOf(allSources);
            gatewayMemory = gatewayMemory == null ? List.of() : List.copyOf(gatewayMemory);
        }

        static ExecutionState initial(AgentRequest request, AgentSystem system, ActorRef<AgentResult> replyTo) {
            return new ExecutionState(
                    request,
                    system,
                    replyTo,
                    delegatesFor(system),
                    0,
                    "None.",
                    "",
                    Map.of(),
                    List.of(),
                    List.of());
        }

        ExecutionState withRag(String ragContext, List<String> sources) {
            return new ExecutionState(
                    request,
                    system,
                    replyTo,
                    delegates,
                    delegateIndex,
                    ragContext,
                    lastStepOutput,
                    delegateOutputs,
                    append(allSources, sources),
                    gatewayMemory);
        }

        ExecutionState advanceDelegate() {
            return new ExecutionState(
                    request,
                    system,
                    replyTo,
                    delegates,
                    delegateIndex + 1,
                    ragContext,
                    lastStepOutput,
                    delegateOutputs,
                    allSources,
                    gatewayMemory);
        }

        ExecutionState withStepResult(AgentStepActor.Result result) {
            Map<String, String> outputs = new LinkedHashMap<>(delegateOutputs);
            outputs.put(result.agentName(), result.output());
            return new ExecutionState(
                    request,
                    system,
                    replyTo,
                    delegates,
                    delegateIndex,
                    ragContext,
                    result.output(),
                    outputs,
                    append(allSources, result.sources()),
                    gatewayMemory);
        }

        ExecutionState withGatewayMemory(List<AgentMemoryEvent> memory) {
            return new ExecutionState(
                    request,
                    system,
                    replyTo,
                    delegates,
                    delegateIndex,
                    ragContext,
                    lastStepOutput,
                    delegateOutputs,
                    allSources,
                    memory);
        }

        List<String> distinctSources() {
            return allSources.stream().distinct().toList();
        }

        private static Map<String, String> immutableLinkedMap(Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        private static List<String> append(List<String> existing, List<String> additions) {
            if (additions == null || additions.isEmpty()) {
                return existing == null ? List.of() : List.copyOf(existing);
            }
            List<String> merged = new java.util.ArrayList<>(existing == null ? List.of() : existing);
            merged.addAll(additions);
            return List.copyOf(merged);
        }
    }
}
