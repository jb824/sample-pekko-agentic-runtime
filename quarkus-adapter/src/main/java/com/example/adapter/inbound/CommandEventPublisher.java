package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CommandEventPublisher {
    private static final Logger LOG = Logger.getLogger(CommandEventPublisher.class);

    @Inject
    @Channel("agent-commands-out")
    Emitter<String> emitter;

    @Inject
    ObjectMapper mapper;

    public String publishNewsArticle(String tenantId, String source, String articleId, String title, String url, Instant occurredAt) throws Exception {
        NormalizedNewsArticleEvent event = new NormalizedNewsArticleEvent(
                UUID.randomUUID().toString(),
                "NewsArticleReceived",
                1,
                tenantId == null || tenantId.isBlank() ? "tenant-default" : tenantId,
                source,
                occurredAt == null ? Instant.now() : occurredAt,
                new NormalizedNewsArticleEvent.NewsArticlePayload(articleId, title, url)
        );
        emitter.send(mapper.writeValueAsString(event))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        LOG.infof("webhook_event event=published topic=agent.commands.v1 event_id=%s tenant_id=%s article_id=%s",
                event.eventId(), event.tenantId(), event.payload().articleId());
        return event.eventId();
    }
}
