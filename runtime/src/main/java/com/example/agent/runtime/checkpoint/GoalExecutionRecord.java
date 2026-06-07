package com.example.agent.runtime.checkpoint;

public record GoalExecutionRecord(
        String goalId,
        String description,
        GoalExecutionStatus status,
        String resultRef,
        String error
) {
    public GoalExecutionRecord {
        goalId = goalId == null ? "" : goalId;
        description = description == null ? "" : description;
        status = status == null ? GoalExecutionStatus.PENDING : status;
        resultRef = resultRef == null ? "" : resultRef;
        error = error == null ? "" : error;
    }
}
