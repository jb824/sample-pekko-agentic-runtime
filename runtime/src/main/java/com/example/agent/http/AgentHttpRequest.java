package com.example.agent.http;

import java.time.Duration;
import java.util.UUID;

public record AgentHttpRequest(String requestId, String input, long timeoutMs) {
    public String resolvedRequestId() {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
    }

    public Duration resolvedTimeout(Duration defaultTimeout) {
        return timeoutMs > 0 ? Duration.ofMillis(timeoutMs) : defaultTimeout;
    }
}
