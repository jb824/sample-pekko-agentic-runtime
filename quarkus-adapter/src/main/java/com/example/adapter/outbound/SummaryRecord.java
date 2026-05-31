package com.example.adapter.outbound;

import java.time.Instant;
import java.util.UUID;

public record SummaryRecord(
        UUID eventId,
        String tenantId,
        String articleId,
        String workflowId,
        String sourceEventId,
        String source,
        String processingStatus,
        String summary,
        String topics,
        String confidence,
        Instant createdAt
) {
    public String sourceRecordId() {
        return sourceEventId;
    }
}
