package com.example.agent.client;

import java.util.List;

public record AgentRunResult(
        String requestId,
        String status,
        String output,
        List<String> sources,
        List<AgentRunError> errors
) {
    public boolean isSuccess() {
        return errors == null || errors.isEmpty();
    }
}
