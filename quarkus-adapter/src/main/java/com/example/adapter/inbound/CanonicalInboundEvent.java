package com.example.adapter.inbound;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

public record CanonicalInboundEvent(
        String eventId,
        String eventType,
        int eventVersion,
        String tenantId,
        String source,
        String sourceRecordId,
        Instant occurredAt,
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = NewsArticlePayload.class, name = "news-article"),
                @JsonSubTypes.Type(value = GoogleBusinessProfilePayload.class, name = "google-business-profile-review"),
                @JsonSubTypes.Type(value = YouTubeCommentPayload.class, name = "youtube-comment")
        })
        Object payload
) {
}
