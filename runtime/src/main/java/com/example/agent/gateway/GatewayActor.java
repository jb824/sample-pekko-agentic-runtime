package com.example.agent.gateway;

import com.example.agent.api.AgentSystem;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentError;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.protocol.AgentStatus;
import com.example.agent.runtime.agent.AgentRunCoordinatorActor;
import com.example.agent.runtime.agent.PromptBudget;
import com.example.agent.runtime.consumer.AgentConsumerRegistryActor;
import com.example.agent.runtime.memory.AgentMemoryRegistryActor;
import com.example.agent.rag.runtime.RagProtocol;
import com.example.agent.runtime.tool.ToolProtocol;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.Objects;

public final class GatewayActor extends AbstractBehavior<GatewayActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry;
    private final ActorRef<RagProtocol.Command> ragRuntime;
    private final ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry;
    private final boolean ragEnabled;
    private final int ragTopK;
    private final int ragMaxContextChars;
    private final Duration executionTimeout;
    private final Duration toolTimeout;
    private final PromptBudget promptBudget;
    private final int maxConcurrentRequests;
    private int inFlightRequests;

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
            int maxConcurrentRequests
    ) {
        return create(
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
                PromptBudget.disabled(),
                maxConcurrentRequests
        );
    }

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
            PromptBudget promptBudget,
            int maxConcurrentRequests
    ) {
        return Behaviors.setup(context -> new GatewayActor(
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
                promptBudget,
                maxConcurrentRequests
        ));
    }

    private GatewayActor(
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
            PromptBudget promptBudget,
            int maxConcurrentRequests
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.memoryRegistry = Objects.requireNonNull(memoryRegistry);
        this.ragRuntime = Objects.requireNonNull(ragRuntime);
        this.consumerRegistry = consumerRegistry;
        this.ragEnabled = ragEnabled;
        this.ragTopK = Math.max(1, ragTopK);
        this.ragMaxContextChars = Math.max(0, ragMaxContextChars);
        this.executionTimeout = Objects.requireNonNull(executionTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
        this.promptBudget = promptBudget == null ? PromptBudget.disabled() : promptBudget;
        this.maxConcurrentRequests = Math.max(1, maxConcurrentRequests);
    }

    public sealed interface Command permits HandleAgentSystemRuntimeRequest, ExecutorStopped {
    }

    public record HandleAgentSystemRuntimeRequest(
            AgentRequest request,
            AgentSystem agentSystem,
            ActorRef<AgentResult> replyTo
    ) implements Command {
    }

    private record ExecutorStopped(String requestId) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleAgentSystemRuntimeRequest.class, this::onHandleAgentSystemRuntimeRequest)
                .onMessage(ExecutorStopped.class, this::onExecutorStopped)
                .build();
    }

    private Behavior<Command> onHandleAgentSystemRuntimeRequest(HandleAgentSystemRuntimeRequest command) {
        if (inFlightRequests >= maxConcurrentRequests) {
            command.replyTo().tell(new AgentResult(
                    command.request().requestId(),
                    AgentStatus.FAILED_SYSTEM,
                    "",
                    java.util.List.of(),
                    java.util.List.of(new AgentError(
                            "gateway_capacity_exceeded",
                            "Gateway in-flight request capacity exceeded: in_flight=" + inFlightRequests
                                    + " max_concurrent_requests=" + maxConcurrentRequests,
                            true,
                            "gateway"
                    ))
            ));
            return this;
        }
        ActorRef<AgentRunCoordinatorActor.Command> executor = getContext().spawn(
                AgentRunCoordinatorActor.create(
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
                ),
                "agent-system-executor-" + command.request().requestId()
        );
        inFlightRequests++;
        getContext().watchWith(executor, new ExecutorStopped(command.request().requestId()));
        executor.tell(new AgentRunCoordinatorActor.Start(command.request(), command.agentSystem(), command.replyTo()));
        return this;
    }

    private Behavior<Command> onExecutorStopped(ExecutorStopped stopped) {
        inFlightRequests = Math.max(0, inFlightRequests - 1);
        getContext().getLog().debug(
                "Agent system executor stopped request_id={} in_flight={}",
                stopped.requestId(),
                inFlightRequests
        );
        return this;
    }
}
