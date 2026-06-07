package com.example.agent.api;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class GoalRun {
    private final AgentComponentClient componentClient;
    private final String goalId;

    GoalRun(AgentComponentClient componentClient, String goalId) {
        this.componentClient = Objects.requireNonNull(componentClient);
        this.goalId = Objects.requireNonNull(goalId);
    }

    public String goalId() {
        return goalId;
    }

    public GoalState get() {
        return getAsync().toCompletableFuture().join();
    }

    public CompletionStage<GoalState> getAsync() {
        return componentClient.forGoal(goalId).getAsync();
    }
}
