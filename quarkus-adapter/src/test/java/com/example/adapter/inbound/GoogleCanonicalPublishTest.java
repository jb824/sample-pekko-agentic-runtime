package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleCanonicalPublishTest {

    @Test
    void publishesGoogleBusinessProfileCanonicalEvent() throws Exception {
        CommandEventPublisher publisher = basePublisher();
        GoogleBusinessProfilePayload payload = new GoogleBusinessProfilePayload(
                "acc-1", "loc-1", "rev-1", "Pat", "FIVE", "Great", "2026-01-01T00:00:00Z");
        String eventId = publisher.publishGoogleBusinessProfileReview("tenant-g", payload, Instant.parse("2026-01-01T00:00:00Z"));

        CanonicalInboundEvent event = publisher.mapper.readValue(capturingEmitter.payload(), CanonicalInboundEvent.class);
        assertEquals(eventId, event.eventId());
        assertEquals("google-business-profile", event.source());
        assertEquals("tenant-g", event.tenantId());
        assertEquals("GoogleBusinessProfileReviewReceived", event.eventType());
        assertEquals("rev-1", event.sourceRecordId());
    }

    @Test
    void publishesYouTubeCanonicalEvent() throws Exception {
        CommandEventPublisher publisher = basePublisher();
        YouTubeCommentPayload payload = new YouTubeCommentPayload(
                "ch-1", "vid-1", "c-1", "Lee", "nice video", "2026-01-01T00:00:00Z", 9L);
        String eventId = publisher.publishYouTubeComment("tenant-y", payload, Instant.parse("2026-01-01T00:00:00Z"));

        CanonicalInboundEvent event = publisher.mapper.readValue(capturingEmitter.payload(), CanonicalInboundEvent.class);
        assertEquals(eventId, event.eventId());
        assertEquals("youtube", event.source());
        assertEquals("tenant-y", event.tenantId());
        assertEquals("YouTubeCommentReceived", event.eventType());
        assertEquals("c-1", event.sourceRecordId());
    }

    private static CapturingEmitter capturingEmitter;

    private static CommandEventPublisher basePublisher() {
        CommandEventPublisher publisher = new CommandEventPublisher();
        publisher.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher.deduplicator = new InboundEventDeduplicator();
        publisher.deduplicator.ttl = Duration.ofHours(24);
        capturingEmitter = new CapturingEmitter();
        publisher.emitter = capturingEmitter;
        return publisher;
    }
}
