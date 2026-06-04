package com.example.agent.gateway;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.agent.DefaultAgentSystemExecutorActor;
import com.example.agent.runtime.agent.AgentSystemDefinition;
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
    private final boolean ragEnabled;
    private final int ragTopK;
    private final int ragMaxContextChars;
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
        return Behaviors.setup(context -> new GatewayActor(
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

    private GatewayActor(
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
        this.ragMaxContextChars = Math.max(0, ragMaxContextChars);
        this.executionTimeout = Objects.requireNonNull(executionTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
    }

    public sealed interface Command permits HandleAgentSystemRuntimeRequest {
    }

    public record HandleAgentSystemRuntimeRequest(
            AgentRequest request,
            AgentSystemDefinition agentSystem,
            ActorRef<AgentResult> replyTo
    ) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleAgentSystemRuntimeRequest.class, this::onHandleAgentSystemRuntimeRequest)
                .build();
    }

    private Behavior<Command> onHandleAgentSystemRuntimeRequest(HandleAgentSystemRuntimeRequest command) {
        ActorRef<DefaultAgentSystemExecutorActor.Command> executor = getContext().spawn(
                DefaultAgentSystemExecutorActor.create(
                        llmWorker,
                        toolRegistry,
                        memoryRegistry,
                        ragRuntime,
                        ragEnabled,
                        ragTopK,
                        ragMaxContextChars,
                        executionTimeout,
                        toolTimeout
                ),
                "agent-system-executor-" + command.request().requestId()
        );
        executor.tell(new DefaultAgentSystemExecutorActor.Start(command.request(), command.agentSystem(), command.replyTo()));
        return this;
    }
}
