package com.example.adapter.inbound;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MockGoogleInboundResourceTest {

    @Test
    void publishesMockGbpReview() throws Exception {
        MockGoogleInboundResource resource = new MockGoogleInboundResource();
        resource.publisher = new StubPublisher();
        Response response = resource.publishGbpReview(new MockGoogleBusinessProfileReviewRequest(
                "tenant-a", "acc-1", "loc-1", "rev-1", "Pat", "FIVE", "Great", "2026-01-01T00:00:00Z"));
        MockGoogleInboundResource.MockAck ack = (MockGoogleInboundResource.MockAck) response.getEntity();
        assertEquals("accepted", ack.status());
        assertNotNull(ack.eventId());
    }

    @Test
    void publishesMockYouTubeComment() throws Exception {
        MockGoogleInboundResource resource = new MockGoogleInboundResource();
        resource.publisher = new StubPublisher();
        Response response = resource.publishYouTubeComment(new MockYouTubeCommentRequest(
                "tenant-a", "ch-1", "v-1", "c-1", "Lee", "Nice", "2026-01-01T00:00:00Z", 3L));
        MockGoogleInboundResource.MockAck ack = (MockGoogleInboundResource.MockAck) response.getEntity();
        assertEquals("accepted", ack.status());
        assertNotNull(ack.eventId());
    }

    private static class StubPublisher extends CommandEventPublisher {
        @Override
        public String publishMockGoogleBusinessProfileReview(String tenantId, GoogleBusinessProfilePayload payload, Instant occurredAt) {
            return "mock-gbp-event";
        }

        @Override
        public String publishMockYouTubeComment(String tenantId, YouTubeCommentPayload payload, Instant occurredAt) {
            return "mock-youtube-event";
        }
    }
}
