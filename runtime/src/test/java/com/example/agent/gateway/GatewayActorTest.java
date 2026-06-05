package com.example.agent.gateway;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTaskDefinition;
import com.example.agent.api.GatewayAgent;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.rag.runtime.RagProtocol;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentStatus;
import com.example.agent.runtime.memory.AgentMemoryRegistryActor;
import com.example.agent.runtime.tool.ToolProtocol;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GatewayActorTest {
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(3);
    private static final AgentMemoryConfig DISABLED_MEMORY = AgentMemoryConfig.disabled();
    private static final AgentTaskDefinition TASK = AgentTaskDefinition.named("agent.request").maxIterations(2).build();
    private static final Agent ASSISTANT = Agent.named("assistant")
            .instructedBy("Answer.")
            .accepts(TASK)
            .memory(DISABLED_MEMORY)
            .build();
    private static final AgentSystem TEST_SYSTEM = AgentSystem.builder()
            .entrypoint(GatewayAgent.named("gateway")
                    .accepts(TASK)
                    .delegatesTo(ASSISTANT)
                    .memory(DISABLED_MEMORY)
                    .build())
            .agent(ASSISTANT)
            .build();

    private final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "gateway-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void rejectsWhenInFlightCapacityIsExceeded() {
        ActorRef<LlmProtocol.Command> llmWorker = system.systemActorOf(
                Behaviors.receiveMessage(command -> Behaviors.same()),
                "llm-worker",
                Props.empty()
        );
        ActorRef<ToolProtocol.Command> toolRegistry = system.systemActorOf(
                Behaviors.receiveMessage(command -> Behaviors.same()),
                "tool-registry",
                Props.empty()
        );
        ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry = system.systemActorOf(
                Behaviors.receiveMessage(command -> Behaviors.same()),
                "memory-registry",
                Props.empty()
        );
        ActorRef<RagProtocol.Command> ragRuntime = system.systemActorOf(
                Behaviors.receiveMessage(command -> Behaviors.same()),
                "rag-runtime",
                Props.empty()
        );
        ActorRef<GatewayActor.Command> gateway = system.systemActorOf(
                GatewayActor.create(
                        llmWorker,
                        toolRegistry,
                        memoryRegistry,
                        ragRuntime,
                        false,
                        1,
                        0,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        1
                ),
                "gateway",
                Props.empty()
        );
        ActorRef<AgentResult> ignoredReply = system.systemActorOf(
                Behaviors.receiveMessage(result -> Behaviors.same()),
                "ignored-reply",
                Props.empty()
        );

        gateway.tell(new GatewayActor.HandleAgentSystemRuntimeRequest(
                new AgentRequest("request-1", "hold open", "tenant-a"),
                TEST_SYSTEM,
                ignoredReply
        ));

        AgentResult rejected = AskPattern.<GatewayActor.Command, AgentResult>ask(
                gateway,
                replyTo -> new GatewayActor.HandleAgentSystemRuntimeRequest(
                        new AgentRequest("request-2", "reject", "tenant-a"),
                        TEST_SYSTEM,
                        replyTo
                ),
                ASK_TIMEOUT,
                system.scheduler()
        ).toCompletableFuture().join();

        assertEquals(AgentStatus.FAILED_SYSTEM, rejected.status());
        assertEquals("gateway_capacity_exceeded", rejected.errors().getFirst().code());
    }
}
