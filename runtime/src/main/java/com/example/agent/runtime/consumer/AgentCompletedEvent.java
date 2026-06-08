package com.example.agent.runtime.consumer;

import com.example.agent.protocol.AgentStatus;

import java.time.Instant;
import java.util.List;

public record AgentCompletedEvent(
        String requestId,
        String tenantId,
        String gatewayName,
        String originalInput,
        String finalOutput,
        AgentStatus status,
        List<String> sources,
        long latencyMs,
        Instant completedAt
) {
    public AgentCompletedEvent {
        requestId = requestId == null ? "" : requestId;
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        gatewayName = gatewayName == null ? "" : gatewayName;
        originalInput = originalInput == null ? "" : originalInput;
        finalOutput = finalOutput == null ? "" : finalOutput;
        sources = sources == null ? List.of() : List.copyOf(sources);
        latencyMs = Math.max(0L, latencyMs);
        completedAt = completedAt == null ? Instant.EPOCH : completedAt;
    }
}
