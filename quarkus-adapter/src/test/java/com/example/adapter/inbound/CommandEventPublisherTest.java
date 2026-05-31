package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandEventPublisherTest {

    @Test
    void publishNewsArticleUsesDefaultTenantAndWaitsForSend() throws Exception {
        CommandEventPublisher publisher = new CommandEventPublisher();
        publisher.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher.deduplicator = new InboundEventDeduplicator();
        publisher.deduplicator.ttl = java.time.Duration.ofHours(24);
        CapturingEmitter emitter = new CapturingEmitter();
        publisher.emitter = emitter;

        String eventId = publisher.publishNewsArticle(
                "",
                "guardian",
                "article-1",
                "title",
                "https://example.com",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        CanonicalInboundEvent event = publisher.mapper.readValue(emitter.payload(), CanonicalInboundEvent.class);
        assertEquals(event.eventId(), eventId);
        assertEquals("tenant-default", event.tenantId());
        assertEquals("article-1", event.sourceRecordId());
    }

}
