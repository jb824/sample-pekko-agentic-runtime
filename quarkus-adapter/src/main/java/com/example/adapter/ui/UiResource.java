package com.example.adapter.ui;

import com.example.adapter.inbound.CommandEventPublisher;
import com.example.adapter.inbound.GoogleBusinessProfilePayload;
import com.example.adapter.inbound.YouTubeCommentPayload;
import com.example.adapter.outbound.CassandraSummaryWriter;
import com.example.adapter.outbound.SummaryRecord;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

@Path("/")
@ApplicationScoped
public class UiResource {
    private static final Logger LOG = Logger.getLogger(UiResource.class);

    @Inject
    CassandraSummaryWriter writer;

    @Inject
    CommandEventPublisher publisher;

    @Inject
    @Location("UiResource/index")
    Template indexTemplate;

    @Inject
    @Location("UiResource/summaryRows")
    Template summaryRowsTemplate;

    @Inject
    @Location("UiResource/webhookResult")
    Template webhookResultTemplate;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(
            @QueryParam("tenantId") @DefaultValue("tenant-default") String tenantId,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        int boundedLimit = boundLimit(limit);
        List<SummaryRecord> summaries = writer.latestByTenant(tenantId, boundedLimit);
        return indexTemplate
                .data("tenantId", tenantId)
                .data("limit", boundedLimit)
                .data("summaries", summaries);
    }

    @GET
    @Path("/ui/summaries")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance summaries(
            @QueryParam("tenantId") @DefaultValue("tenant-default") String tenantId,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        int boundedLimit = boundLimit(limit);
        List<SummaryRecord> summaries = writer.latestByTenant(tenantId, boundedLimit);
        return summaryRowsTemplate.data("summaries", summaries);
    }

    @POST
    @Path("/ui/webhook")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance publishWebhook(
            @FormParam("tenantId") String tenantId,
            @FormParam("articleId") String articleId,
            @FormParam("title") String title,
            @FormParam("url") String url
    ) {
        try {
            String eventId = publisher.publishNewsArticle(
                    tenantId,
                    "guardian",
                    articleId,
                    title,
                    url,
                    Instant.now()
            );
            LOG.infof("ui_webhook event=published event_id=%s article_id=%s", eventId, articleId);
            return webhookResultTemplate
                    .data("success", true)
                    .data("message", "Accepted. Event ID: " + eventId);
        } catch (Exception exception) {
            LOG.errorf(exception, "ui_webhook event=failed article_id=%s", articleId);
            return webhookResultTemplate
                    .data("success", false)
                    .data("message", "Publish failed: " + exception.getMessage());
        }
    }

    @POST
    @Path("/ui/mock/gbp-review")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance publishMockGbpReview(
            @FormParam("tenantId") String tenantId,
            @FormParam("accountId") String accountId,
            @FormParam("locationId") String locationId,
            @FormParam("reviewId") String reviewId,
            @FormParam("reviewerDisplayName") String reviewerDisplayName,
            @FormParam("starRating") String starRating,
            @FormParam("comment") String comment
    ) {
        try {
            Instant now = Instant.now();
            GoogleBusinessProfilePayload payload = new GoogleBusinessProfilePayload(
                    accountId,
                    locationId,
                    reviewId,
                    reviewerDisplayName,
                    starRating,
                    comment,
                    now.toString()
            );
            String eventId = publisher.publishMockGoogleBusinessProfileReview(tenantId, payload, now);
            LOG.infof("ui_mock_google event=published type=gbp_review event_id=%s review_id=%s", eventId, reviewId);
            return webhookResultTemplate
                    .data("success", true)
                    .data("message", "Accepted mock GBP review. Event ID: " + eventId);
        } catch (Exception exception) {
            LOG.errorf(exception, "ui_mock_google event=failed type=gbp_review review_id=%s", reviewId);
            return webhookResultTemplate
                    .data("success", false)
                    .data("message", "Mock publish failed: " + exception.getMessage());
        }
    }

    @POST
    @Path("/ui/mock/youtube-comment")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance publishMockYouTubeComment(
            @FormParam("tenantId") String tenantId,
            @FormParam("channelId") String channelId,
            @FormParam("videoId") String videoId,
            @FormParam("commentId") String commentId,
            @FormParam("authorDisplayName") String authorDisplayName,
            @FormParam("textDisplay") String textDisplay,
            @FormParam("likeCount") @DefaultValue("0") long likeCount
    ) {
        try {
            Instant now = Instant.now();
            YouTubeCommentPayload payload = new YouTubeCommentPayload(
                    channelId,
                    videoId,
                    commentId,
                    authorDisplayName,
                    textDisplay,
                    now.toString(),
                    likeCount
            );
            String eventId = publisher.publishMockYouTubeComment(tenantId, payload, now);
            LOG.infof("ui_mock_google event=published type=youtube_comment event_id=%s comment_id=%s", eventId, commentId);
            return webhookResultTemplate
                    .data("success", true)
                    .data("message", "Accepted mock YouTube comment. Event ID: " + eventId);
        } catch (Exception exception) {
            LOG.errorf(exception, "ui_mock_google event=failed type=youtube_comment comment_id=%s", commentId);
            return webhookResultTemplate
                    .data("success", false)
                    .data("message", "Mock publish failed: " + exception.getMessage());
        }
    }

    private static int boundLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
