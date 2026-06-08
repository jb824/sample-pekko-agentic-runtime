package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.GoalDefinition;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.protocol.AgentStatus;
import com.example.agent.runtime.memory.AgentMemoryEventType;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultAgentRunCoordinatorTest {
    private static final AgentMemoryConfig DISABLED_MEMORY = AgentMemoryConfig.disabled();
    private final ActorSystem<AgentResult> system = ActorSystem.create(
            Behaviors.ignore(),
            "coordinator-test-" + UUID.randomUUID()
    );
    private final DefaultAgentRunCoordinator coordinator = new DefaultAgentRunCoordinator(PromptBudget.disabled());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void workflowDrivenNextRunsDelegateInOrder() {
        CoordinatorDecision decision = coordinator.next(state(workflowDrivenSystem(2)));

        var run = assertInstanceOf(CoordinatorDecision.RunDelegate.class, decision);
        assertEquals("assistant", run.delegate().name());
    }

    @Test
    void workflowDrivenAfterDelegatesSynthesizesGateway() {
        DefaultAgentRunState state = state(workflowDrivenSystem(1)).advanceDelegate();

        assertInstanceOf(CoordinatorDecision.SynthesizeGateway.class, coordinator.next(state));
    }

    @Test
    void modelDrivenNextRoutesThroughGatewayBeforeMaxIterations() {
        CoordinatorDecision decision = coordinator.next(state(modelDrivenSystem(2)));

        var route = assertInstanceOf(CoordinatorDecision.RouteWithGateway.class, decision);
        assertTrue(route.prompt().contains("DELEGATE: agent-name"));
    }

    @Test
    void modelDrivenNextSynthesizesWhenMaxIterationsReached() {
        DefaultAgentRunState state = state(modelDrivenSystem(1)).advanceDelegate();

        assertInstanceOf(CoordinatorDecision.SynthesizeGateway.class, coordinator.next(state));
    }

    @Test
    void gatewayRouteFinalCompletesRunAndWritesMemory() {
        CoordinatorDecision decision = coordinator.onGatewayRouteResponse(
                state(modelDrivenSystem(2)),
                new LlmProtocol.Response("request-1", "FINAL: done", null)
        );

        var complete = assertInstanceOf(CoordinatorDecision.CompleteRun.class, decision);
        assertEquals(AgentStatus.COMPLETED, complete.result().status());
        assertEquals("done", complete.result().output());
        assertEquals(AgentMemoryEventType.FINAL_ANSWER, complete.memoryWrites().getFirst().type());
    }

    @Test
    void gatewayRouteDelegateRunsSelectedDelegate() {
        CoordinatorDecision decision = coordinator.onGatewayRouteResponse(
                state(modelDrivenSystem(2)),
                new LlmProtocol.Response("request-1", "DELEGATE: assistant", null)
        );

        var run = assertInstanceOf(CoordinatorDecision.RunDelegate.class, decision);
        assertEquals("assistant", run.delegate().name());
    }

    @Test
    void gatewayRouteUnknownDelegateFailsRun() {
        CoordinatorDecision decision = coordinator.onGatewayRouteResponse(
                state(modelDrivenSystem(2)),
                new LlmProtocol.Response("request-1", "DELEGATE: missing", null)
        );

        var complete = assertInstanceOf(CoordinatorDecision.CompleteRun.class, decision);
        assertEquals(AgentStatus.FAILED_SYSTEM, complete.result().status());
        assertEquals("gateway_route_invalid", complete.result().errors().getFirst().code());
    }

    @Test
    void gatewayResponseSuccessCompletesAndWritesTaskAndAnswerMemory() {
        CoordinatorDecision decision = coordinator.onGatewayResponse(
                state(workflowDrivenSystem(1)),
                new LlmProtocol.Response("request-1", "answer", null)
        );

        var complete = assertInstanceOf(CoordinatorDecision.CompleteRun.class, decision);
        assertEquals(AgentStatus.COMPLETED, complete.result().status());
        assertEquals(2, complete.memoryWrites().size());
    }

    @Test
    void timeoutCompletesWithTimeoutStatus() {
        CoordinatorDecision decision = coordinator.onTimeout(state(workflowDrivenSystem(1)));

        var complete = assertInstanceOf(CoordinatorDecision.CompleteRun.class, decision);
        assertEquals(AgentStatus.TIMEOUT, complete.result().status());
    }

    private DefaultAgentRunState state(AgentSystem agentSystem) {
        ActorRef<AgentResult> replyTo = system;
        return DefaultAgentRunState.initial(new AgentRequest("request-1", "input"), agentSystem, replyTo);
    }

    private static AgentSystem workflowDrivenSystem(int maxIterations) {
        return system(maxIterations, false);
    }

    private static AgentSystem modelDrivenSystem(int maxIterations) {
        return system(maxIterations, true);
    }

    private static AgentSystem system(int maxIterations, boolean modelDriven) {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer.")
                .memory(DISABLED_MEMORY)
                .build();
        GatewayAgent.Builder gateway = GatewayAgent.named("gateway")
                .accepts(GoalDefinition.named("agent.request").maxIterations(maxIterations).build())
                .delegatesTo(assistant)
                .memory(DISABLED_MEMORY);
        if (modelDriven) {
            gateway.modelDriven();
        }
        return AgentSystem.builder()
                .entrypoint(gateway.build())
                .agent(assistant)
                .build();
    }
}
