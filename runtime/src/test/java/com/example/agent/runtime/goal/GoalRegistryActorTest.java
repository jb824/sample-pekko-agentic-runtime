package com.example.agent.runtime.goal;

import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalDefinition;
import com.example.agent.api.GatewayAgent;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentRuntimeService;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentStatus;
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

final class GoalRegistryActorTest {
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(3);
    private static final AgentMemoryConfig TEST_MEMORY = AgentMemoryConfig.defaultEnabled();
    private static final GoalDefinition TEST_TASK = GoalDefinition.named("test.task").maxIterations(1).build();
    private static final AgentSystem TEST_SYSTEM = AgentSystem.builder()
            .entrypoint(GatewayAgent.named("gateway")
                    .accepts(TEST_TASK)
                    .memory(TEST_MEMORY)
                    .build())
            .build();

    private final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "agent-task-registry-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void expiresCompletedTasksAfterRetentionWindow() {
        MutableClock clock = new MutableClock();
        ActorRef<GoalRegistryActor.Command> registry = system.systemActorOf(
                GoalRegistryActor.create(
                        completedRuntimeService(),
                        Duration.ofSeconds(5),
                        10,
                        clock
                ),
                "registry-expiry",
                Props.empty()
        );

        startTask(registry, "task-expiry");
        assertEquals(GoalStatus.COMPLETED, awaitState(registry, "task-expiry").status());

        clock.advance(Duration.ofSeconds(6));

        GoalState expired = getTask(registry, "task-expiry");
        assertEquals(GoalStatus.NOT_FOUND, expired.status());
    }

    @Test
    void trimsOldestCompletedTasksWhenRetentionLimitIsExceeded() {
        MutableClock clock = new MutableClock();
        ActorRef<GoalRegistryActor.Command> registry = system.systemActorOf(
                GoalRegistryActor.create(
                        completedRuntimeService(),
                        Duration.ofHours(1),
                        2,
                        clock
                ),
                "registry-limit",
                Props.empty()
        );

        startTask(registry, "task-1");
        assertEquals(GoalStatus.COMPLETED, awaitState(registry, "task-1").status());
        clock.advance(Duration.ofSeconds(1));

        startTask(registry, "task-2");
        assertEquals(GoalStatus.COMPLETED, awaitState(registry, "task-2").status());
        clock.advance(Duration.ofSeconds(1));

        startTask(registry, "task-3");
        assertEquals(GoalStatus.COMPLETED, awaitState(registry, "task-3").status());

        assertEquals(GoalStatus.NOT_FOUND, getTask(registry, "task-1").status());
        assertEquals(GoalStatus.COMPLETED, getTask(registry, "task-2").status());
        assertEquals(GoalStatus.COMPLETED, getTask(registry, "task-3").status());
    }

    private AgentRuntimeService completedRuntimeService() {
        return new AgentRuntimeService() {
            @Override
            public CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystem agentSystem, Duration timeout) {
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

    private void startTask(ActorRef<GoalRegistryActor.Command> registry, String goalId) {
        AskPattern.<GoalRegistryActor.Command, GoalState>ask(
                registry,
                replyTo -> new GoalRegistryActor.StartGoal(goalId, "input", Duration.ofSeconds(10), TEST_SYSTEM, replyTo),
                ASK_TIMEOUT,
                system.scheduler()
        ).toCompletableFuture().join();
    }

    private GoalState getTask(ActorRef<GoalRegistryActor.Command> registry, String goalId) {
        return AskPattern.<GoalRegistryActor.Command, GoalState>ask(
                registry,
                replyTo -> new GoalRegistryActor.GetGoal(goalId, replyTo),
                ASK_TIMEOUT,
                system.scheduler()
        ).toCompletableFuture().join();
    }

    private GoalState awaitState(ActorRef<GoalRegistryActor.Command> registry, String goalId) {
        GoalState state = getTask(registry, goalId);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (state.status() == GoalStatus.RUNNING && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            state = getTask(registry, goalId);
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
