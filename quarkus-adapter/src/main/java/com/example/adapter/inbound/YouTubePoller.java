package com.example.adapter.inbound;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class YouTubePoller {
    private static final Logger LOG = Logger.getLogger(YouTubePoller.class);
    private static final String DEFAULT_YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";

    @Inject
    CommandEventPublisher publisher;

    @Inject
    GoogleCredentialsProvider credentialsProvider;

    @ConfigProperty(name = "google.youtube.poll.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "google.youtube.tenant-id", defaultValue = "tenant-default")
    String tenantId;

    @ConfigProperty(name = "google.youtube.channel-id", defaultValue = "")
    Optional<String> channelId;

    @ConfigProperty(name = "google.youtube.poll.max-results", defaultValue = "25")
    long maxResults;

    @ConfigProperty(name = "google.youtube.scope", defaultValue = DEFAULT_YOUTUBE_SCOPE)
    String youtubeScope;

    private boolean warnedInactive;
    private boolean warnedMissingCredentials;
    private boolean warnedInsufficientScope;

    @Scheduled(every = "{google.youtube.poll.interval:10m}", delayed = "25s")
    void poll() {
        String configuredChannelId = channelId.orElse("");
        if (!enabled) {
            return;
        }
        if (configuredChannelId.isBlank()) {
            warnInactive("missing_channel_id");
            return;
        }
        if (!credentialsProvider.credentialsAvailable()) {
            warnMissingCredentials();
            return;
        }
        try {
            GoogleCredentials credentials = credentialsProvider.scoped(List.of(youtubeScope));
            HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentials);
            HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            YouTube youtube = new YouTube.Builder(transport, GsonFactory.getDefaultInstance(), initializer)
                    .setApplicationName("pekko-agent-quarkus-adapter")
                    .build();

            YouTube.CommentThreads.List list = youtube.commentThreads()
                    .list(List.of("snippet"))
                    .setAllThreadsRelatedToChannelId(configuredChannelId)
                    .setMaxResults(maxResults)
                    .setOrder("time")
                    .setTextFormat("plainText");
            CommentThreadListResponse response = list.execute();
            int published = 0;
            if (response.getItems() != null) {
                for (CommentThread thread : response.getItems()) {
                    var top = thread.getSnippet().getTopLevelComment();
                    if (top == null || top.getId() == null) {
                        continue;
                    }
                    var snippet = top.getSnippet();
                    Instant occurredAt = parseInstant(snippet.getUpdatedAt() != null
                            ? snippet.getUpdatedAt().toStringRfc3339() : null);
                    if (occurredAt == null) {
                        occurredAt = parseInstant(snippet.getPublishedAt() != null
                                ? snippet.getPublishedAt().toStringRfc3339() : null);
                    }
                    if (occurredAt == null) {
                        continue;
                    }
                    YouTubeCommentPayload payload = new YouTubeCommentPayload(
                            configuredChannelId,
                            snippet.getVideoId(),
                            top.getId(),
                            snippet.getAuthorDisplayName(),
                            snippet.getTextDisplay(),
                            snippet.getPublishedAt() != null ? snippet.getPublishedAt().toStringRfc3339() : null,
                            snippet.getLikeCount()
                    );
                    publisher.publishYouTubeComment(tenantId, payload, occurredAt);
                    published++;
                }
            }
            LOG.infof("youtube_poll event=completed published=%d", published);
        } catch (GoogleJsonResponseException exception) {
            if (isInsufficientScope(exception)) {
                warnInsufficientScope(exception);
                return;
            }
            LOG.error("youtube_poll event=error", exception);
        } catch (Exception exception) {
            LOG.error("youtube_poll event=error", exception);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void warnInactive(String reason) {
        if (!warnedInactive) {
            LOG.warnf("youtube_poll event=inactive reason=%s mock_only=true channel_configured=%s",
                    reason,
                    channelId.isPresent() && !channelId.get().isBlank());
            warnedInactive = true;
        }
    }

    private void warnMissingCredentials() {
        if (!warnedMissingCredentials) {
            LOG.warnf("youtube_poll event=inactive reason=missing_google_credentials credential_source=%s mock_only=true",
                    credentialsProvider.credentialSourceDescription());
            warnedMissingCredentials = true;
        }
    }

    private void warnInsufficientScope(GoogleJsonResponseException exception) {
        if (!warnedInsufficientScope) {
            LOG.warnf("youtube_poll event=inactive reason=insufficient_auth_scope status=%d configured_scope=%s required_scope=%s action=\"Re-authorize GOOGLE_CREDENTIALS_PATH credentials with the required scope, then restart Quarkus.\"",
                    exception.getStatusCode(),
                    youtubeScope,
                    DEFAULT_YOUTUBE_SCOPE);
            warnedInsufficientScope = true;
        }
    }

    private static boolean isInsufficientScope(GoogleJsonResponseException exception) {
        return exception.getStatusCode() == 403
                && exception.getDetails() != null
                && exception.getDetails().getMessage() != null
                && exception.getDetails().getMessage().toLowerCase().contains("insufficient");
    }
}
