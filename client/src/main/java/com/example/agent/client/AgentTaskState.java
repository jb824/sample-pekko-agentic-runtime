package com.example.agent.client;

public record AgentTaskState(
        String taskId,
        String status,
        AgentRunResult result,
        String error
) {
    public boolean isComplete() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "NOT_FOUND".equals(status);
    }
}
