package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class GuardianPoller {
    private static final Logger LOG = Logger.getLogger(GuardianPoller.class);

    @Inject
    CommandEventPublisher publisher;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "guardian.poll.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "guardian.poll.interval", defaultValue = "10m")
    Duration pollInterval;

    @ConfigProperty(name = "guardian.poll.page-size", defaultValue = "50")
    int pageSize;

    @ConfigProperty(name = "guardian.poll.max-pages", defaultValue = "5")
    int maxPages;

    @ConfigProperty(name = "guardian.tenant-id", defaultValue = "tenant-default")
    String tenantId;

    @ConfigProperty(name = "guardian.api.key")
    String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Scheduled(every = "{guardian.poll.interval}", delayed = "5s")
    void poll() {
        if (!enabled) {
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("guardian_poll event=skipped reason=missing_api_key");
            return;
        }

        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(pollInterval);
        LOG.infof("guardian_poll event=started from=%s to=%s poll_interval=%s", from, to, pollInterval);

        int published = 0;
        int seen = 0;
        try {
            for (int page = 1; page <= maxPages; page++) {
                URI uri = buildSearchUri(from, to, page);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(20))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                LOG.infof("guardian_poll event=http_response page=%d status=%d", page, response.statusCode());
                if (response.statusCode() != 200) {
                    LOG.warnf("guardian_poll event=failed page=%d status=%d body=%s",
                            page, response.statusCode(), truncate(response.body(), 512));
                    break;
                }
                JsonNode root = mapper.readTree(response.body());
                JsonNode results = root.path("response").path("results");
                if (!results.isArray() || results.isEmpty()) {
                    LOG.infof("guardian_poll event=no_results page=%d", page);
                    break;
                }
                for (JsonNode result : results) {
                    seen++;
                    String articleId = result.path("id").asText("");
                    String title = result.path("webTitle").asText("");
                    String url = result.path("webUrl").asText("");
                    Instant occurredAt = parseInstant(result.path("webPublicationDate").asText(""));
                    if (articleId.isBlank() || title.isBlank() || url.isBlank()) {
                        LOG.warnf("guardian_poll event=skipped_invalid_record page=%d id=%s", page, articleId);
                        continue;
                    }
                    if (occurredAt == null) {
                        LOG.warnf("guardian_poll event=skipped_invalid_timestamp page=%d id=%s", page, articleId);
                        continue;
                    }
                    publisher.publishNewsArticle(tenantId, "guardian", articleId, title, url, occurredAt);
                    published++;
                }
            }
            LOG.infof("guardian_poll event=completed from=%s to=%s seen=%d published=%d", from, to, seen, published);
        } catch (Exception exception) {
            LOG.errorf(exception, "guardian_poll event=error from=%s to=%s seen=%d published=%d", from, to, seen, published);
        }
    }

    private URI buildSearchUri(Instant from, Instant to, int page) {
        StringBuilder builder = new StringBuilder("https://content.guardianapis.com/search");
        builder.append("?api-key=").append(encode(apiKey));
        builder.append("&from-date=").append(encode(from.toString()));
        builder.append("&to-date=").append(encode(to.toString()));
        builder.append("&order-by=newest");
        builder.append("&page-size=").append(pageSize);
        builder.append("&page=").append(page);
        return URI.create(builder.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
