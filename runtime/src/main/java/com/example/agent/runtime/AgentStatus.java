package com.example.agent.runtime;

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
