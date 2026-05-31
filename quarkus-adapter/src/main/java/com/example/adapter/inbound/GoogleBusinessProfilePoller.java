package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class GoogleBusinessProfilePoller {
    private static final Logger LOG = Logger.getLogger(GoogleBusinessProfilePoller.class);
    private static final String GBP_SCOPE = "https://www.googleapis.com/auth/business.manage";

    @Inject
    CommandEventPublisher publisher;

    @Inject
    GoogleCredentialsProvider credentialsProvider;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "google.gbp.poll.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "google.gbp.tenant-id", defaultValue = "tenant-default")
    String tenantId;

    @ConfigProperty(name = "google.gbp.account-id", defaultValue = "")
    Optional<String> accountId;

    @ConfigProperty(name = "google.gbp.location-id", defaultValue = "")
    Optional<String> locationId;

    @ConfigProperty(name = "google.gbp.poll.page-size", defaultValue = "25")
    int pageSize;

    private boolean warnedInactive;
    private boolean warnedMissingCredentials;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Scheduled(every = "{google.gbp.poll.interval:10m}", delayed = "20s")
    void poll() {
        String configuredAccountId = accountId.orElse("");
        String configuredLocationId = locationId.orElse("");
        if (!enabled) {
            return;
        }
        if (configuredAccountId.isBlank() || configuredLocationId.isBlank()) {
            warnInactive("missing_source_ids");
            return;
        }
        if (!credentialsProvider.credentialsAvailable()) {
            warnMissingCredentials();
            return;
        }
        try {
            GoogleCredentials credentials = credentialsProvider.scoped(List.of(GBP_SCOPE));
            AccessToken token = credentials.getAccessToken();
            if (token == null || token.getTokenValue() == null) {
                credentials.refreshIfExpired();
                token = credentials.getAccessToken();
            }
            URI uri = URI.create("https://mybusiness.googleapis.com/v4/accounts/" + configuredAccountId
                    + "/locations/" + configuredLocationId + "/reviews?pageSize=" + pageSize);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token.getTokenValue())
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("gbp_poll event=failed status=%d body=%s", response.statusCode(), truncate(response.body(), 512));
                return;
            }
            JsonNode reviews = mapper.readTree(response.body()).path("reviews");
            if (!reviews.isArray()) {
                return;
            }
            int published = 0;
            for (JsonNode review : reviews) {
                String reviewName = review.path("name").asText("");
                String reviewId = reviewName.isBlank() ? "" : reviewName.substring(reviewName.lastIndexOf('/') + 1);
                Instant occurredAt = parseInstant(review.path("updateTime").asText(""));
                if (reviewId.isBlank() || occurredAt == null) {
                    continue;
                }
                GoogleBusinessProfilePayload payload = new GoogleBusinessProfilePayload(
                        configuredAccountId,
                        configuredLocationId,
                        reviewId,
                        review.path("reviewer").path("displayName").asText(""),
                        review.path("starRating").asText(""),
                        review.path("comment").asText(""),
                        review.path("updateTime").asText("")
                );
                publisher.publishGoogleBusinessProfileReview(tenantId, payload, occurredAt);
                published++;
            }
            LOG.infof("gbp_poll event=completed published=%d", published);
        } catch (Exception exception) {
            LOG.error("gbp_poll event=error", exception);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private void warnInactive(String reason) {
        if (!warnedInactive) {
            LOG.warnf("gbp_poll event=inactive reason=%s mock_only=true account_configured=%s location_configured=%s",
                    reason,
                    accountId.isPresent() && !accountId.get().isBlank(),
                    locationId.isPresent() && !locationId.get().isBlank());
            warnedInactive = true;
        }
    }

    private void warnMissingCredentials() {
        if (!warnedMissingCredentials) {
            LOG.warnf("gbp_poll event=inactive reason=missing_google_credentials credential_source=%s mock_only=true",
                    credentialsProvider.credentialSourceDescription());
            warnedMissingCredentials = true;
        }
    }
}
