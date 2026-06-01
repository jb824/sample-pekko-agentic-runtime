package com.example.adapter.inbound;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Activity;
import com.google.api.services.youtube.model.ActivityContentDetails;
import com.google.api.services.youtube.model.ActivityListResponse;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.ResourceId;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class YouTubeChannelActivityPoller {
    private static final Logger LOG = Logger.getLogger(YouTubeChannelActivityPoller.class);
    private static final String DEFAULT_YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";

    @Inject
    CommandEventPublisher publisher;

    @Inject
    GoogleCredentialsProvider credentialsProvider;

    @ConfigProperty(name = "google.youtube.activity.poll.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "google.youtube.tenant-id", defaultValue = "tenant-default")
    String tenantId;

    @ConfigProperty(name = "google.youtube.channel-id", defaultValue = "")
    Optional<String> channelId;

    @ConfigProperty(name = "google.youtube.activity.poll.max-results", defaultValue = "10")
    long maxResults;

    @ConfigProperty(name = "google.youtube.activity.poll.lookback", defaultValue = "PT24H")
    String lookback;

    @ConfigProperty(name = "google.youtube.scope", defaultValue = DEFAULT_YOUTUBE_SCOPE)
    String youtubeScope;

    private boolean warnedInactive;
    private boolean warnedMissingCredentials;
    private boolean warnedInsufficientScope;

    @Scheduled(every = "{google.youtube.activity.poll.interval:15m}", delayed = "30s")
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
            YouTube youtube = youtubeClient();
            int snapshots = publishChannelSnapshot(youtube, configuredChannelId);
            int activities = publishActivities(youtube, configuredChannelId);
            LOG.infof("youtube_channel_activity_poll event=completed snapshots=%d activities=%d", snapshots, activities);
        } catch (GoogleJsonResponseException exception) {
            if (isInsufficientScope(exception)) {
                warnInsufficientScope(exception);
                return;
            }
            LOG.error("youtube_channel_activity_poll event=error", exception);
        } catch (Exception exception) {
            LOG.error("youtube_channel_activity_poll event=error", exception);
        }
    }

    private YouTube youtubeClient() throws Exception {
        GoogleCredentials credentials = credentialsProvider.scoped(List.of(youtubeScope));
        HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentials);
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        return new YouTube.Builder(transport, GsonFactory.getDefaultInstance(), initializer)
                .setApplicationName("pekko-agent-quarkus-adapter")
                .build();
    }

    private int publishChannelSnapshot(YouTube youtube, String configuredChannelId) throws Exception {
        ChannelListResponse response = youtube.channels()
                .list(List.of("snippet", "statistics", "contentDetails"))
                .setId(List.of(configuredChannelId))
                .execute();
        int published = 0;
        if (response.getItems() == null) {
            return 0;
        }
        for (Channel channel : response.getItems()) {
            if (channel.getId() == null) {
                continue;
            }
            var snippet = channel.getSnippet();
            var statistics = channel.getStatistics();
            var relatedPlaylists = channel.getContentDetails() == null ? null : channel.getContentDetails().getRelatedPlaylists();
            YouTubeChannelSnapshotPayload payload = new YouTubeChannelSnapshotPayload(
                    channel.getId(),
                    snippet == null ? "" : snippet.getTitle(),
                    snippet == null ? "" : snippet.getDescription(),
                    snippet == null ? "" : snippet.getCountry(),
                    snippet == null ? "" : snippet.getCustomUrl(),
                    snippet != null && snippet.getPublishedAt() != null ? snippet.getPublishedAt().toStringRfc3339() : "",
                    statistics == null ? null : longValue(statistics.getSubscriberCount()),
                    statistics != null && Boolean.TRUE.equals(statistics.getHiddenSubscriberCount()),
                    statistics == null ? null : longValue(statistics.getViewCount()),
                    statistics == null ? null : longValue(statistics.getVideoCount()),
                    statistics == null ? null : longValue(statistics.getCommentCount()),
                    relatedPlaylists == null ? "" : relatedPlaylists.getUploads()
            );
            publisher.publishYouTubeChannelSnapshot(tenantId, payload, Instant.now());
            published++;
        }
        return published;
    }

    private int publishActivities(YouTube youtube, String configuredChannelId) throws Exception {
        Instant publishedAfter = Instant.now().minus(java.time.Duration.parse(lookback));
        ActivityListResponse response = youtube.activities()
                .list(List.of("snippet", "contentDetails"))
                .setChannelId(configuredChannelId)
                .setMaxResults(maxResults)
                .setPublishedAfter(publishedAfter.toString())
                .execute();
        int published = 0;
        if (response.getItems() == null) {
            return 0;
        }
        for (Activity activity : response.getItems()) {
            if (activity.getId() == null || activity.getSnippet() == null) {
                continue;
            }
            Instant occurredAt = parseInstant(activity.getSnippet().getPublishedAt() == null
                    ? null : activity.getSnippet().getPublishedAt().toStringRfc3339());
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
            YouTubeChannelActivityPayload payload = new YouTubeChannelActivityPayload(
                    activity.getId(),
                    activity.getSnippet().getChannelId(),
                    activity.getSnippet().getChannelTitle(),
                    activity.getSnippet().getType(),
                    activity.getSnippet().getTitle(),
                    activity.getSnippet().getDescription(),
                    activity.getSnippet().getPublishedAt() == null ? "" : activity.getSnippet().getPublishedAt().toStringRfc3339(),
                    videoId(activity.getContentDetails()),
                    playlistId(activity.getContentDetails()),
                    playlistItemId(activity.getContentDetails()),
                    channelItemId(activity.getContentDetails()),
                    recommendationReason(activity.getContentDetails())
            );
            publisher.publishYouTubeChannelActivity(tenantId, payload, occurredAt);
            published++;
        }
        return published;
    }

    private static Long longValue(BigInteger value) {
        return value == null ? null : value.longValue();
    }

    private static String videoId(ActivityContentDetails details) {
        if (details == null) {
            return "";
        }
        if (details.getUpload() != null) {
            return details.getUpload().getVideoId();
        }
        ResourceId resourceId = resourceId(details);
        return resourceId == null ? "" : value(resourceId.getVideoId());
    }

    private static String playlistId(ActivityContentDetails details) {
        if (details == null || details.getPlaylistItem() == null) {
            return "";
        }
        return value(details.getPlaylistItem().getPlaylistId());
    }

    private static String playlistItemId(ActivityContentDetails details) {
        if (details == null || details.getPlaylistItem() == null) {
            return "";
        }
        return value(details.getPlaylistItem().getPlaylistItemId());
    }

    private static String channelItemId(ActivityContentDetails details) {
        ResourceId resourceId = resourceId(details);
        return resourceId == null ? "" : value(resourceId.getChannelId());
    }

    private static String recommendationReason(ActivityContentDetails details) {
        return details != null && details.getRecommendation() != null
                ? value(details.getRecommendation().getReason()) : "";
    }

    private static ResourceId resourceId(ActivityContentDetails details) {
        if (details == null) {
            return null;
        }
        if (details.getPlaylistItem() != null) {
            return details.getPlaylistItem().getResourceId();
        }
        if (details.getRecommendation() != null) {
            return details.getRecommendation().getResourceId();
        }
        if (details.getComment() != null) {
            return details.getComment().getResourceId();
        }
        if (details.getLike() != null) {
            return details.getLike().getResourceId();
        }
        if (details.getFavorite() != null) {
            return details.getFavorite().getResourceId();
        }
        if (details.getSubscription() != null) {
            return details.getSubscription().getResourceId();
        }
        if (details.getChannelItem() != null) {
            return details.getChannelItem().getResourceId();
        }
        return null;
    }

    private static String value(String value) {
        return value == null ? "" : value;
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
            LOG.warnf("youtube_channel_activity_poll event=inactive reason=%s mock_only=true channel_configured=%s",
                    reason,
                    channelId.isPresent() && !channelId.get().isBlank());
            warnedInactive = true;
        }
    }

    private void warnMissingCredentials() {
        if (!warnedMissingCredentials) {
            LOG.warnf("youtube_channel_activity_poll event=inactive reason=missing_google_credentials credential_source=%s mock_only=true",
                    credentialsProvider.credentialSourceDescription());
            warnedMissingCredentials = true;
        }
    }

    private void warnInsufficientScope(GoogleJsonResponseException exception) {
        if (!warnedInsufficientScope) {
            LOG.warnf("youtube_channel_activity_poll event=inactive reason=insufficient_auth_scope status=%d configured_scope=%s required_scope=%s action=\"Re-authorize GOOGLE_CREDENTIALS_PATH credentials with the required scope, then restart Quarkus.\"",
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
