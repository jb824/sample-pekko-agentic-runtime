package com.example.adapter.outbound;

public record NewsSummaryGeneratedEvent(
        String eventId,
        String eventType,
        int eventVersion,
        String tenantId,
        String workflowId,
        String sourceEventId,
        String source,
        SummaryPayload payload
) {
    public record SummaryPayload(
            String articleId,
            String summary,
            String topics,
            String confidence
    ) {
    }
}
