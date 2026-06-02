package com.example.agent.runtime.task;

import com.example.agent.runtime.AgentResult;

public record AgentTaskState(
        String taskId,
        AgentTaskStatus status,
        AgentResult result,
        String error
) {
    public static AgentTaskState running(String taskId) {
        return new AgentTaskState(taskId, AgentTaskStatus.RUNNING, null, "");
    }

    public static AgentTaskState completed(String taskId, AgentResult result) {
        return new AgentTaskState(taskId, AgentTaskStatus.COMPLETED, result, "");
    }

    public static AgentTaskState failed(String taskId, String error) {
        return new AgentTaskState(taskId, AgentTaskStatus.FAILED, null, error == null ? "" : error);
    }

    public static AgentTaskState notFound(String taskId) {
        return new AgentTaskState(taskId, AgentTaskStatus.NOT_FOUND, null, "Task not found.");
    }
}
