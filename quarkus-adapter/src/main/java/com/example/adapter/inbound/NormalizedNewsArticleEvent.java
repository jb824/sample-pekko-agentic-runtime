package com.example.adapter.inbound;

import java.time.Instant;

public record NormalizedNewsArticleEvent(
        String eventId,
        String eventType,
        int eventVersion,
        String tenantId,
        String source,
        Instant occurredAt,
        NewsArticlePayload payload
) {
    public record NewsArticlePayload(
            String articleId,
            String title,
            String url
    ) {
    }
}
