package com.example.adapter.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class HackerNewsPoller {
    private static final Logger LOG = Logger.getLogger(HackerNewsPoller.class);

    @Inject
    CommandEventPublisher publisher;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "hackernews.poll.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "hackernews.poll.interval", defaultValue = "10m")
    Duration pollInterval;

    @ConfigProperty(name = "hackernews.poll.max-item-checks", defaultValue = "100")
    int maxItemChecks;

    @ConfigProperty(name = "hackernews.api.base-url", defaultValue = "https://hacker-news.firebaseio.com")
    String baseUrl;

    @ConfigProperty(name = "hackernews.tenant-id", defaultValue = "tenant-default")
    String tenantId;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Scheduled(every = "{hackernews.poll.interval}", delayed = "15s")
    void poll() {
        if (!enabled) {
            return;
        }
        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(pollInterval);
        long fromEpoch = from.getEpochSecond();
        LOG.infof("hackernews_poll event=started from=%s to=%s poll_interval=%s max_item_checks=%d",
                from, to, pollInterval, maxItemChecks);

        int checked = 0;
        int published = 0;
        try {
            HttpResponse<String> idsResponse = get(baseUrl + "/v0/newstories.json");
            if (idsResponse.statusCode() != 200) {
                LOG.warnf("hackernews_poll event=failed stage=newstories status=%d body=%s",
                        idsResponse.statusCode(), truncate(idsResponse.body(), 512));
                return;
            }

            JsonNode ids = mapper.readTree(idsResponse.body());
            if (!ids.isArray() || ids.isEmpty()) {
                LOG.info("hackernews_poll event=no_story_ids");
                return;
            }

            for (JsonNode idNode : ids) {
                if (checked >= maxItemChecks) {
                    break;
                }
                long id = idNode.asLong(-1);
                if (id <= 0) {
                    continue;
                }
                checked++;
                HttpResponse<String> itemResponse = get(baseUrl + "/v0/item/" + id + ".json");
                if (itemResponse.statusCode() != 200) {
                    LOG.warnf("hackernews_poll event=item_fetch_failed id=%d status=%d",
                            id, itemResponse.statusCode());
                    continue;
                }
                JsonNode item = mapper.readTree(itemResponse.body());
                if (!"story".equals(item.path("type").asText())) {
                    continue;
                }
                long itemTime = item.path("time").asLong(0);
                if (itemTime < fromEpoch) {
                    continue;
                }
                String articleId = "hn/" + id;
                String title = item.path("title").asText("");
                String url = item.path("url").asText("");
                if (title.isBlank() || url.isBlank()) {
                    continue;
                }
                publisher.publishNewsArticle(
                        tenantId,
                        "hackernews",
                        articleId,
                        title,
                        url,
                        Instant.ofEpochSecond(itemTime)
                );
                published++;
            }

            LOG.infof("hackernews_poll event=completed from=%s to=%s checked=%d published=%d",
                    from, to, checked, published);
        } catch (Exception exception) {
            LOG.errorf(exception, "hackernews_poll event=error from=%s to=%s checked=%d published=%d",
                    from, to, checked, published);
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
