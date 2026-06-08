package com.example.agent.api;

import com.example.agent.protocol.AgentResult;

public record GoalState(
        String goalId,
        String status,
        AgentResult result,
        String error
) {
    public boolean isComplete() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "NOT_FOUND".equals(status);
    }
}
