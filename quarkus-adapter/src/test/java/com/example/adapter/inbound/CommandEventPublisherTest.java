package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandEventPublisherTest {

    @Test
    void publishNewsArticleUsesDefaultTenantAndWaitsForSend() throws Exception {
        CommandEventPublisher publisher = new CommandEventPublisher();
        publisher.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Emitter<String> emitter = mock(Emitter.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(emitter.send(payloadCaptor.capture())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.emitter = emitter;

        String eventId = publisher.publishNewsArticle(
                "",
                "guardian",
                "article-1",
                "title",
                "https://example.com",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        NormalizedNewsArticleEvent event = publisher.mapper.readValue(payloadCaptor.getValue(), NormalizedNewsArticleEvent.class);
        assertEquals(event.eventId(), eventId);
        assertEquals("tenant-default", event.tenantId());
    }

}
