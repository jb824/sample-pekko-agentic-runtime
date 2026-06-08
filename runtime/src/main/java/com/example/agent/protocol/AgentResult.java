package com.example.agent.protocol;

import java.util.List;

public record AgentResult(
        String requestId,
        AgentStatus status,
        String output,
        List<String> sources,
        List<AgentError> errors
) {
    public boolean isSuccess() {
        return status == AgentStatus.COMPLETED || status == AgentStatus.DEGRADED;
    }
}
