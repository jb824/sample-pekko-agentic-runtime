package com.example.agent.runtime.task;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentRuntimeService;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentStatus;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.runtime.agent.GatewayAgentDefinition;
import com.example.agent.runtime.agent.MemoryDefinition;
import com.example.agent.runtime.agent.TaskDefinition;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AgentTaskRegistryActorTest {
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(3);
    private static final MemoryDefinition TEST_MEMORY = new MemoryDefinition(true, 20, true, true, true, true, true);
    private static final AgentSystemDefinition TEST_SYSTEM = new AgentSystemDefinition(
            new GatewayAgentDefinition("gateway", "", List.of(), TEST_MEMORY, new TaskDefinition("test.task", 1), List.of()),
            List.of()
    );

    private final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "agent-task-registry-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void expiresCompletedTasksAfterRetentionWindow() {
        MutableClock clock = new MutableClock();
        ActorRef<AgentTaskRegistryActor.Command> registry = system.systemActorOf(
                AgentTaskRegistryActor.create(
                        completedRuntimeService(),
                        Duration.ofSeconds(5),
                        10,
                        clock
                ),
                "registry-expiry",
                Props.empty()
        );

        startTask(registry, "task-expiry");
        assertEquals(AgentTaskStatus.COMPLETED, awaitState(registry, "task-expiry").status());

        clock.advance(Duration.ofSeconds(6));

        AgentTaskState expired = getTask(registry, "task-expiry");
        assertEquals(AgentTaskStatus.NOT_FOUND, expired.status());
    }

    @Test
    void trimsOldestCompletedTasksWhenRetentionLimitIsExceeded() {
        MutableClock clock = new MutableClock();
        ActorRef<AgentTaskRegistryActor.Command> registry = system.systemActorOf(
                AgentTaskRegistryActor.create(
                        completedRuntimeService(),
                        Duration.ofHours(1),
                        2,
                        clock
                ),
                "registry-limit",
                Props.empty()
        );

        startTask(registry, "task-1");
        assertEquals(AgentTaskStatus.COMPLETED, awaitState(registry, "task-1").status());
        clock.advance(Duration.ofSeconds(1));

        startTask(registry, "task-2");
        assertEquals(AgentTaskStatus.COMPLETED, awaitState(registry, "task-2").status());
        clock.advance(Duration.ofSeconds(1));

        startTask(registry, "task-3");
        assertEquals(AgentTaskStatus.COMPLETED, awaitState(registry, "task-3").status());

        assertEquals(AgentTaskStatus.NOT_FOUND, getTask(registry, "task-1").status());
        assertEquals(AgentTaskStatus.COMPLETED, getTask(registry, "task-2").status());
        assertEquals(AgentTaskStatus.COMPLETED, getTask(registry, "task-3").status());
    }

    private AgentRuntimeService completedRuntimeService() {
        return new AgentRuntimeService() {
            @Override
            public CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystemDefinition agentSystem, Duration timeout) {
                return completed(request.requestId());
            }

            private CompletionStage<AgentResult> completed(String requestId) {
                return CompletableFuture.completedFuture(new AgentResult(
                        requestId,
                        AgentStatus.COMPLETED,
                        "ok",
                        List.of(),
                        List.of()
                ));
            }
        };
    }

    private void startTask(ActorRef<AgentTaskRegistryActor.Command> registry, String taskId) {
        AskPattern.<AgentTaskRegistryActor.Command, AgentTaskState>ask(
                registry,
                replyTo -> new AgentTaskRegistryActor.StartTask(taskId, "input", Duration.ofSeconds(10), TEST_SYSTEM, replyTo),
                ASK_TIMEOUT,
                system.scheduler()
        ).toCompletableFuture().join();
    }

    private AgentTaskState getTask(ActorRef<AgentTaskRegistryActor.Command> registry, String taskId) {
        return AskPattern.<AgentTaskRegistryActor.Command, AgentTaskState>ask(
                registry,
                replyTo -> new AgentTaskRegistryActor.GetTask(taskId, replyTo),
                ASK_TIMEOUT,
                system.scheduler()
        ).toCompletableFuture().join();
    }

    private AgentTaskState awaitState(ActorRef<AgentTaskRegistryActor.Command> registry, String taskId) {
        AgentTaskState state = getTask(registry, taskId);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (state.status() == AgentTaskStatus.RUNNING && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            state = getTask(registry, taskId);
        }
        return state;
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.EPOCH;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
