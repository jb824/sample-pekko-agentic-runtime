package com.example.agent.runtime.checkpoint;

public record TaskExecutionRecord(
        String taskId,
        String description,
        TaskExecutionStatus status,
        String resultRef,
        String error
) {
    public TaskExecutionRecord {
        taskId = taskId == null ? "" : taskId;
        description = description == null ? "" : description;
        status = status == null ? TaskExecutionStatus.PENDING : status;
        resultRef = resultRef == null ? "" : resultRef;
        error = error == null ? "" : error;
    }
}
