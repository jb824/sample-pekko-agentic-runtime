package com.example.agent.api;

import com.example.agent.runtime.goal.GoalState;
import com.example.agent.runtime.goal.GoalRegistryActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class GatewayAgentClient {
    private final ActorRef<GoalRegistryActor.Command> goalRegistry;
    private final Scheduler scheduler;
    private final Duration defaultTimeout;
    private final AgentSystem system;
    private final String instanceId;

    GatewayAgentClient(
            ActorRef<GoalRegistryActor.Command> goalRegistry,
            Scheduler scheduler,
            Duration defaultTimeout,
            AgentSystem system,
            String instanceId
    ) {
        this.goalRegistry = Objects.requireNonNull(goalRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
        this.system = Objects.requireNonNull(system);
        this.instanceId = Objects.requireNonNull(instanceId);
    }

    public String runSingleGoal(GoalRequest goal) {
        return runSingleGoal(goal, defaultTimeout);
    }

    public String runSingleGoal(GoalRequest goal, Duration timeout) {
        return runSingleGoalAsync(goal, timeout).toCompletableFuture().join();
    }

    public CompletionStage<String> runSingleGoalAsync(GoalRequest goal) {
        return runSingleGoalAsync(goal, defaultTimeout);
    }

    public CompletionStage<String> runSingleGoalAsync(GoalRequest goal, Duration timeout) {
        String goalId = instanceId + "-" + UUID.randomUUID();
        return AskPattern.<GoalRegistryActor.Command, GoalState>ask(
                goalRegistry,
                replyTo -> new GoalRegistryActor.StartGoal(
                        goalId,
                        goal.instructions(),
                        timeout,
                        system,
                        replyTo
                ),
                Duration.ofSeconds(5),
                scheduler
        ).thenApply(state -> state.goalId());
    }
}
