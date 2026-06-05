package com.example.agent.runtime.checkpoint;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CASSANDRA_INTEGRATION", matches = "true")
final class WorkflowEntityCassandraIntegrationTest {
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void recoversCheckpointAfterActorSystemRestart() {
        String workflowId = "workflow-" + UUID.randomUUID();
        AgentCheckpoint checkpoint = AgentCheckpoint.initial("tenant-a", workflowId, "conversation-1", "agent-1")
                .withPlan("recover from cassandra")
                .withContextManifestRef("agent_context/context_manifest_by_agent/tenant-a/agent-1/" + workflowId + "/1")
                .withStep(WorkflowStep.TOOL_EXECUTION)
                .withLastEventSeqNr(7L);

        ActorSystem<Void> firstSystem = ActorSystem.create(Behaviors.empty(), "workflow-cassandra-recovery-first");
        try {
            ActorRef<WorkflowEntityActor.Command> entity = firstSystem.systemActorOf(
                    WorkflowEntityActor.create(workflowId),
                    "workflow-entity",
                    Props.empty()
            );
            WorkflowEntityActor.State initialized = ask(firstSystem, entity, replyTo -> new WorkflowEntityActor.Initialize(checkpoint, replyTo));
            assertTrue(initialized.initialized());
            assertEquals(WorkflowStep.TOOL_EXECUTION, initialized.checkpoint().orElseThrow().currentStep());
        } finally {
            firstSystem.terminate();
            firstSystem.getWhenTerminated().toCompletableFuture().join();
        }

        ActorSystem<Void> secondSystem = ActorSystem.create(Behaviors.empty(), "workflow-cassandra-recovery-second");
        try {
            ActorRef<WorkflowEntityActor.Command> recoveredEntity = secondSystem.systemActorOf(
                    WorkflowEntityActor.create(workflowId),
                    "workflow-entity",
                    Props.empty()
            );
            WorkflowEntityActor.State recovered = ask(secondSystem, recoveredEntity, WorkflowEntityActor.GetState::new);

            assertTrue(recovered.initialized());
            assertEquals(workflowId, recovered.checkpoint().orElseThrow().workflowId());
            assertEquals("recover from cassandra", recovered.checkpoint().orElseThrow().plan());
            assertEquals("tenant-a", recovered.checkpoint().orElseThrow().tenantId());
            assertEquals(WorkflowStep.TOOL_EXECUTION, recovered.checkpoint().orElseThrow().currentStep());
            assertEquals(7L, recovered.checkpoint().orElseThrow().lastEventSeqNr());
        } finally {
            secondSystem.terminate();
            secondSystem.getWhenTerminated().toCompletableFuture().join();
        }
    }

    private static WorkflowEntityActor.State ask(
            ActorSystem<Void> system,
            ActorRef<WorkflowEntityActor.Command> entity,
            java.util.function.Function<ActorRef<WorkflowEntityActor.State>, WorkflowEntityActor.Command> message
    ) {
        return AskPattern.ask(entity, message::apply, ASK_TIMEOUT, system.scheduler())
                .toCompletableFuture()
                .join();
    }
}
