package com.example.agent.runtime.memory;

import java.time.Instant;
import java.util.Objects;

public record AgentMemoryEvent(
        Instant occurredAt,
        AgentMemoryEventType type,
        String requestId,
        String content
) {
    public AgentMemoryEvent {
        occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
        type = Objects.requireNonNull(type);
        requestId = requestId == null ? "" : requestId;
        content = content == null ? "" : content;
    }
}
