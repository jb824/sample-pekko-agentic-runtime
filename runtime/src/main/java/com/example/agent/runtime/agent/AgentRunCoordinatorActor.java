package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GatewayAgent;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.rag.runtime.RagProtocol;
import com.example.agent.runtime.consumer.AgentCompletedEvent;
import com.example.agent.runtime.consumer.AgentConsumerRegistryActor;
import com.example.agent.runtime.consumer.CompletionDispatcher;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AgentRunCoordinatorActor extends AbstractBehavior<AgentRunCoordinatorActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry;
    private final ActorRef<RagProtocol.Command> ragRuntime;
    private final CompletionDispatcher completionDispatcher;
    private final ToolSetupStage toolSetupStage;
    private final GlobalRagStage globalRagStage;
    private final DefaultAgentRunCoordinator coordinator;
    private final Duration executionTimeout;
    private final Duration toolTimeout;
    private final PromptBudget promptBudget;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry,
            ActorRef<RagProtocol.Command> ragRuntime,
            ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry,
            boolean ragEnabled,
            int ragTopK,
            int ragMaxContextChars,
            Duration executionTimeout,
            Duration toolTimeout,
            PromptBudget promptBudget
    ) {
        return Behaviors.setup(context -> new AgentRunCoordinatorActor(
                context,
                llmWorker,
                toolRegistry,
                memoryRegistry,
                ragRuntime,
                consumerRegistry,
                ragEnabled,
                ragTopK,
                ragMaxContextChars,
                executionTimeout,
                toolTimeout,
                promptBudget
        ));
    }

    private AgentRunCoordinatorActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry,
            ActorRef<RagProtocol.Command> ragRuntime,
            ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry,
            boolean ragEnabled,
            int ragTopK,
            int ragMaxContextChars,
            Duration executionTimeout,
            Duration toolTimeout,
            PromptBudget promptBudget
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.memoryRegistry = Objects.requireNonNull(memoryRegistry);
        this.ragRuntime = Objects.requireNonNull(ragRuntime);
        this.completionDispatcher = new CompletionDispatcher(consumerRegistry);
        this.toolSetupStage = new ToolSetupStage();
        this.globalRagStage = new GlobalRagStage(ragEnabled, ragTopK, ragMaxContextChars);
        this.executionTimeout = Objects.requireNonNull(executionTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
        this.promptBudget = promptBudget == null ? PromptBudget.disabled() : promptBudget;
        this.coordinator = new DefaultAgentRunCoordinator(this.promptBudget);
    }

    public sealed interface Command permits Start, ExecutionTimeout, WrappedToolsRegistered, WrappedRagResult, WrappedStepResult, WrappedGatewayRouteResponse, WrappedGatewayMemoryRecall, WrappedGatewayResponse {
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

    private record WrappedGatewayRouteResponse(LlmProtocol.Response response) implements Command {
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
        DefaultAgentRunState state = DefaultAgentRunState.initial(start.request(), start.system(), start.replyTo());
        getContext().scheduleOnce(executionTimeout, getContext().getSelf(), new ExecutionTimeout());
        getContext().getLog().info(
                "Agent system accepted request={} gateway={} delegates={}",
                state.request().requestId(), state.system().entrypoint().name(), state.system().entrypoint().delegates());

        if (toolSetupStage.requiresRegistration(state)) {
            ActorRef<ToolProtocol.ToolsRegistered> adapter =
                    getContext().messageAdapter(ToolProtocol.ToolsRegistered.class, WrappedToolsRegistered::new);
            toolRegistry.tell(toolSetupStage.registerTools(state, adapter));
            return waitingForToolRegistration(state);
        }
        return afterRegistration(state);
    }

    private Behavior<Command> waitingForToolRegistration(DefaultAgentRunState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedToolsRegistered.class, msg -> onToolsRegistered(state, msg))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onToolsRegistered(DefaultAgentRunState state, WrappedToolsRegistered msg) {
        getContext().getLog().debug(
                "Tools registered request={} count={}",
                state.request().requestId(), msg.registered().registeredCount());
        return afterRegistration(state);
    }

    private Behavior<Command> afterRegistration(DefaultAgentRunState state) {
        if (globalRagStage.enabled()) {
            ActorRef<RagProtocol.Retrieved> adapter =
                    getContext().messageAdapter(RagProtocol.Retrieved.class, WrappedRagResult::new);
            ragRuntime.tell(globalRagStage.retrieve(state, adapter));
            return waitingForRag(state);
        }
        return runNextDelegate(state);
    }

    private Behavior<Command> waitingForRag(DefaultAgentRunState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedRagResult.class, wrapped -> onRagResult(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onRagResult(DefaultAgentRunState state, WrappedRagResult wrapped) {
        if (wrapped.retrieved().isSuccess() && wrapped.retrieved().result() != null) {
            getContext().getLog().info("RAG completed request={} chunks={}",
                    state.request().requestId(), wrapped.retrieved().result().chunks().size());
        } else {
            getContext().getLog().warn("RAG failed or empty request={}", state.request().requestId());
        }
        return runNextDelegate(globalRagStage.applyRetrieved(state, wrapped.retrieved()));
    }

    private Behavior<Command> runNextDelegate(DefaultAgentRunState state) {
        return interpret(coordinator.next(state));
    }

    private Behavior<Command> askGatewayRoute(DefaultAgentRunState state, String prompt) {
        ActorRef<LlmProtocol.Response> adapter =
                getContext().messageAdapter(LlmProtocol.Response.class, WrappedGatewayRouteResponse::new);
        llmWorker.tell(new LlmProtocol.Ask(
                state.request().requestId() + ":gateway:" + state.system().entrypoint().name() + ":route:" + state.delegateIndex(),
                prompt,
                adapter));
        return waitingForGatewayRoute(state);
    }

    private Behavior<Command> waitingForGatewayRoute(DefaultAgentRunState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedGatewayRouteResponse.class, wrapped -> onGatewayRouteResponse(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onGatewayRouteResponse(DefaultAgentRunState state, WrappedGatewayRouteResponse wrapped) {
        return interpret(coordinator.onGatewayRouteResponse(state, wrapped.response()));
    }

    private Behavior<Command> spawnDelegateStep(DefaultAgentRunState state, Agent delegate) {
        ActorRef<AgentStepActor.Result> stepReply =
                getContext().messageAdapter(AgentStepActor.Result.class, WrappedStepResult::new);
        ActorRef<AgentStepActor.Command> stepActor = getContext().spawn(
                AgentStepActor.create(
                        llmWorker,
                        toolRegistry,
                        ragRuntime,
                        memoryRegistry,
                        state.request(),
                        state.system().entrypoint().name(),
                        delegate,
                        state.system().entrypoint().acceptedGoal(),
                        state.request().input(),
                        state.lastStepOutput(),
                        state.ragContext(),
                        state.system().toolDefinitions(),
                        globalRagStage.topK(),
                        toolTimeout,
                        promptBudget,
                        stepReply),
                "step-" + state.delegateIndex() + "-" + delegate.name() + "-" + state.request().requestId());
        getContext().getLog().info(
                "Spawning step actor request={} agent={} hasPrior={}",
                state.request().requestId(), delegate.name(), !state.lastStepOutput().isEmpty());
        stepActor.tell(new AgentStepActor.Start());
        return runningDelegate(state);
    }

    private Behavior<Command> runningDelegate(DefaultAgentRunState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedStepResult.class, wrapped -> onStepResult(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onStepResult(DefaultAgentRunState state, WrappedStepResult wrapped) {
        AgentStepActor.Result result = wrapped.result();
        if (result.isSuccess()) {
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
        return interpret(coordinator.onStepResult(state, result));
    }

    private Behavior<Command> runGatewaySynthesis(DefaultAgentRunState state) {
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

    private Behavior<Command> waitingForGatewayMemory(DefaultAgentRunState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedGatewayMemoryRecall.class, wrapped -> onGatewayMemoryRecall(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onGatewayMemoryRecall(DefaultAgentRunState state, WrappedGatewayMemoryRecall msg) {
        List<AgentMemoryEvent> memory = msg.recalled().events() == null ? List.of() : msg.recalled().events();
        return askGatewayLlm(state.withGatewayMemory(memory));
    }

    private Behavior<Command> askGatewayLlm(DefaultAgentRunState state) {
        ActorRef<LlmProtocol.Response> adapter =
                getContext().messageAdapter(LlmProtocol.Response.class, WrappedGatewayResponse::new);
        String prompt = GatewayPromptBuilder.build(
                state.request(),
                state.system().entrypoint(),
                state.delegateOutputs(),
                state.gatewayMemory(),
                state.ragContext(),
                promptBudget);
        if (prompt.length() > promptBudget.maxPromptChars()) {
            AgentResult result = coordinator.contextBudgetFailure(state, "gateway");
            state.replyTo().tell(result);
            dispatchCompleted(state, result);
            return Behaviors.stopped();
        }
        llmWorker.tell(new LlmProtocol.Ask(
                state.request().requestId() + ":gateway:" + state.system().entrypoint().name(),
                prompt,
                adapter));
        return waitingForGatewayLlm(state);
    }

    private Behavior<Command> waitingForGatewayLlm(DefaultAgentRunState state) {
        return Behaviors.receive(Command.class)
                .onMessage(WrappedGatewayResponse.class, wrapped -> onGatewayResponse(state, wrapped))
                .onMessage(ExecutionTimeout.class, ignored -> onExecutionTimeout(state))
                .build();
    }

    private Behavior<Command> onGatewayResponse(DefaultAgentRunState state, WrappedGatewayResponse wrapped) {
        return interpret(coordinator.onGatewayResponse(state, wrapped.response()));
    }

    private Behavior<Command> onExecutionTimeout(DefaultAgentRunState state) {
        return interpret(coordinator.onTimeout(state));
    }

    private Behavior<Command> interpret(CoordinatorDecision decision) {
        if (decision instanceof CoordinatorDecision.RunDelegate run) {
            return spawnDelegateStep(run.state(), run.delegate());
        }
        if (decision instanceof CoordinatorDecision.RouteWithGateway route) {
            return askGatewayRoute(route.state(), route.prompt());
        }
        if (decision instanceof CoordinatorDecision.SynthesizeGateway synthesize) {
            return runGatewaySynthesis(synthesize.state());
        }
        if (decision instanceof CoordinatorDecision.CompleteRun complete) {
            complete.memoryWrites().forEach(write -> rememberGateway(complete.state(), write.type(), write.content()));
            complete.state().replyTo().tell(complete.result());
            dispatchCompleted(complete.state(), complete.result());
            return Behaviors.stopped();
        }
        throw new IllegalStateException("Unhandled coordinator decision: " + decision);
    }

    private void dispatchCompleted(DefaultAgentRunState state, AgentResult result) {
        completionDispatcher.dispatch(new AgentCompletedEvent(
                state.request().requestId(),
                state.request().tenantId(),
                state.system().entrypoint().name(),
                state.request().input(),
                result.output(),
                result.status(),
                result.sources(),
                System.currentTimeMillis() - state.startedAtMs(),
                Instant.now()
        ));
    }

    private void rememberGateway(DefaultAgentRunState state, AgentMemoryEventType type, String content) {
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
