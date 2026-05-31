package com.example.adapter.outbound;

import java.time.Instant;
import java.util.UUID;

public record SummaryRecord(
        UUID eventId,
        String tenantId,
        String articleId,
        String workflowId,
        String sourceEventId,
        String summary,
        String topics,
        String confidence,
        Instant createdAt
) {
}
