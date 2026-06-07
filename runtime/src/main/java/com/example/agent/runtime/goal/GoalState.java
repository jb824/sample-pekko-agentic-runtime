package com.example.agent.runtime.goal;

import com.example.agent.runtime.AgentResult;

public record GoalState(
        String goalId,
        GoalStatus status,
        AgentResult result,
        String error
) {
    public static GoalState running(String goalId) {
        return new GoalState(goalId, GoalStatus.RUNNING, null, "");
    }

    public static GoalState completed(String goalId, AgentResult result) {
        return new GoalState(goalId, GoalStatus.COMPLETED, result, "");
    }

    public static GoalState failed(String goalId, String error) {
        return new GoalState(goalId, GoalStatus.FAILED, null, error == null ? "" : error);
    }

    public static GoalState notFound(String goalId) {
        return new GoalState(goalId, GoalStatus.NOT_FOUND, null, "Goal not found.");
    }
}
