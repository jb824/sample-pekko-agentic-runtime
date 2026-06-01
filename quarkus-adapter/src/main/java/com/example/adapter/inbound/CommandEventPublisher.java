package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    @Inject
    InboundEventDeduplicator deduplicator;

    public String publishNewsArticle(String tenantId, String source, String articleId, String title, String url, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String normalizedTenant = normalizeTenant(tenantId);
        String eventId = stableEventId(source, articleId, timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "NewsArticleReceived",
                1,
                normalizedTenant,
                source,
                articleId,
                timestamp,
                new NewsArticlePayload(articleId, title, url)
        );
        return publish(event, "article_id=" + articleId);
    }

    public String publishGoogleBusinessProfileReview(String tenantId, GoogleBusinessProfilePayload payload, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String eventId = stableEventId("google-business-profile", payload.reviewId(), timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "GoogleBusinessProfileReviewReceived",
                1,
                normalizeTenant(tenantId),
                "google-business-profile",
                payload.reviewId(),
                timestamp,
                payload
        );
        return publish(event, "review_id=" + payload.reviewId());
    }

    public String publishYouTubeComment(String tenantId, YouTubeCommentPayload payload, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String eventId = stableEventId("youtube", payload.commentId(), timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "YouTubeCommentReceived",
                1,
                normalizeTenant(tenantId),
                "youtube",
                payload.commentId(),
                timestamp,
                payload
        );
        return publish(event, "comment_id=" + payload.commentId());
    }

    public String publishYouTubeChannelSnapshot(String tenantId, YouTubeChannelSnapshotPayload payload, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String eventId = stableEventId("youtube", payload.channelId() + ":snapshot", timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "YouTubeChannelSnapshotCaptured",
                1,
                normalizeTenant(tenantId),
                "youtube",
                payload.channelId(),
                timestamp,
                payload
        );
        return publish(event, "channel_id=" + payload.channelId() + " subscriber_count=" + payload.subscriberCount());
    }

    public String publishYouTubeChannelActivity(String tenantId, YouTubeChannelActivityPayload payload, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String eventId = stableEventId("youtube", payload.activityId(), timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "YouTubeChannelActivityCaptured",
                1,
                normalizeTenant(tenantId),
                "youtube",
                payload.activityId(),
                timestamp,
                payload
        );
        return publish(event, "activity_id=" + payload.activityId() + " activity_type=" + payload.activityType());
    }

    public String publishMockGoogleBusinessProfileReview(String tenantId, GoogleBusinessProfilePayload payload, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String eventId = stableEventId("mock-google", payload.reviewId(), timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "MockGoogleBusinessProfileReviewReceived",
                1,
                normalizeTenant(tenantId),
                "mock-google",
                payload.reviewId(),
                timestamp,
                payload
        );
        return publish(event, "mock_review_id=" + payload.reviewId());
    }

    public String publishMockYouTubeComment(String tenantId, YouTubeCommentPayload payload, Instant occurredAt) throws Exception {
        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        String eventId = stableEventId("mock-google", payload.commentId(), timestamp.toString());
        CanonicalInboundEvent event = new CanonicalInboundEvent(
                eventId,
                "MockYouTubeCommentReceived",
                1,
                normalizeTenant(tenantId),
                "mock-google",
                payload.commentId(),
                timestamp,
                payload
        );
        return publish(event, "mock_comment_id=" + payload.commentId());
    }

    private String publish(CanonicalInboundEvent event, String recordLabel) throws Exception {
        if (!deduplicator.firstTime(event.eventId())) {
            LOG.infof("ingest_event event=deduplicated source=%s event_id=%s %s",
                    event.source(), event.eventId(), recordLabel);
            return event.eventId();
        }
        emitter.send(mapper.writeValueAsString(event))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        LOG.infof("ingest_event event=published topic=agent.commands.v1 event_id=%s tenant_id=%s source=%s %s",
                event.eventId(), event.tenantId(), event.source(), recordLabel);
        return event.eventId();
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "tenant-default" : tenantId;
    }

    private static String stableEventId(String source, String recordId, String occurredAt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = source + "|" + (recordId == null ? "" : recordId) + "|" + occurredAt;
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception exception) {
            return UUID.randomUUID().toString();
        }
    }
}
