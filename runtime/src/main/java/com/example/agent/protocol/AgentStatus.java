package com.example.agent.protocol;

public enum AgentStatus {
    COMPLETED,
    DEGRADED,
    TOOL_EXHAUSTED,
    TIMEOUT,
    POLICY_REJECTED,
    FAILED_SYSTEM;

    public boolean isSuccess() {
        return this == COMPLETED || this == DEGRADED;
    }
}
