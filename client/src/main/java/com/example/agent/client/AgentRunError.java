package com.example.agent.client;

public record AgentRunError(
        String code,
        String message,
        boolean retryable,
        String component
) {
}
