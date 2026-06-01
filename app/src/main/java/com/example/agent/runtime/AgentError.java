package com.example.agent.runtime;

public record AgentError(
        String code,
        String message,
        boolean retryable,
        String component
) {
}
