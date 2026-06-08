package com.example.agent.protocol;

public record AgentError(
        String code,
        String message,
        boolean retryable,
        String component
) {
}
