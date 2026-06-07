package com.example.agent.api;

import com.example.agent.runtime.goal.GoalRegistryActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class GoalClient {
    private final ActorRef<GoalRegistryActor.Command> goalRegistry;
    private final Scheduler scheduler;
    private final Duration defaultTimeout;
    private final String goalId;

    GoalClient(
            ActorRef<GoalRegistryActor.Command> goalRegistry,
            Scheduler scheduler,
            Duration defaultTimeout,
            String goalId
    ) {
        this.goalRegistry = Objects.requireNonNull(goalRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
        this.goalId = Objects.requireNonNull(goalId);
    }

    public GoalState get() {
        return getAsync().toCompletableFuture().join();
    }

    public CompletionStage<GoalState> getAsync() {
        return AskPattern.<GoalRegistryActor.Command, com.example.agent.runtime.goal.GoalState>ask(
                goalRegistry,
                replyTo -> new GoalRegistryActor.GetGoal(goalId, replyTo),
                Duration.ofSeconds(5),
                scheduler
        ).thenApply(state -> new GoalState(
                state.goalId(),
                state.status().name(),
                state.result(),
                state.error()
        ));
    }
}
