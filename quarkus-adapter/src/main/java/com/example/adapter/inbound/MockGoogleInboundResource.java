package com.example.adapter.inbound;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;

@Path("/mock/google")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MockGoogleInboundResource {
    private static final Logger LOG = Logger.getLogger(MockGoogleInboundResource.class);

    @Inject
    CommandEventPublisher publisher;

    @POST
    @Path("/gbp-review")
    public Response publishGbpReview(@Valid MockGoogleBusinessProfileReviewRequest request) throws Exception {
        GoogleBusinessProfilePayload payload = new GoogleBusinessProfilePayload(
                request.accountId(),
                request.locationId(),
                request.reviewId(),
                request.reviewerDisplayName(),
                request.starRating(),
                request.comment(),
                request.updateTime()
        );
        Instant occurredAt = parseInstant(request.updateTime());
        String eventId = publisher.publishMockGoogleBusinessProfileReview(request.tenantId(), payload, occurredAt);
        LOG.infof("mock_google_event event=published type=gbp_review event_id=%s review_id=%s", eventId, request.reviewId());
        return Response.ok(new MockAck("accepted", eventId)).build();
    }

    @POST
    @Path("/youtube-comment")
    public Response publishYouTubeComment(@Valid MockYouTubeCommentRequest request) throws Exception {
        YouTubeCommentPayload payload = new YouTubeCommentPayload(
                request.channelId(),
                request.videoId(),
                request.commentId(),
                request.authorDisplayName(),
                request.textDisplay(),
                request.publishedAt(),
                request.likeCount()
        );
        Instant occurredAt = parseInstant(request.publishedAt());
        String eventId = publisher.publishMockYouTubeComment(request.tenantId(), payload, occurredAt);
        LOG.infof("mock_google_event event=published type=youtube_comment event_id=%s comment_id=%s", eventId, request.commentId());
        return Response.ok(new MockAck("accepted", eventId)).build();
    }

    private static Instant parseInstant(String input) {
        try {
            return input == null || input.isBlank() ? Instant.now() : Instant.parse(input);
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    public record MockAck(String status, String eventId) {
    }
}
