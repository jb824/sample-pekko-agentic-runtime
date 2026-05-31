package com.example.agent.guardian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class GuardianClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final int pageSize;
    private final Duration timeout;

    public GuardianClient(Config config) {
        Config guardian = config.getConfig("guardian.api");
        this.baseUrl = guardian.getString("base-url");
        this.apiKey = guardian.hasPath("key") ? guardian.getString("key") : "";
        this.pageSize = guardian.getInt("page-size");
        this.timeout = guardian.getDuration("timeout");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public List<GuardianArticle> search(String query, String section, int maxPages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GUARDIAN_API_KEY is missing");
        }
        List<GuardianArticle> articles = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            JsonNode response = searchPage(query, section, page);
            JsonNode root = response.path("response");
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                break;
            }
            for (JsonNode result : results) {
                articles.add(toArticle(result));
            }
            int currentPage = root.path("currentPage").asInt(page);
            int pages = root.path("pages").asInt(page);
            if (currentPage >= pages) {
                break;
            }
        }
        return articles;
    }

    private JsonNode searchPage(String query, String section, int page) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/search?")
                .append("api-key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8))
                .append("&q=").append(encodedQuery)
                .append("&order-by=newest")
                .append("&page-size=").append(pageSize)
                .append("&page=").append(page)
                .append("&show-fields=headline,trailText,byline,publication");
        if (section != null && !section.isBlank()) {
            url.append("&section=").append(URLEncoder.encode(section, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Guardian API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Guardian API request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Guardian API request interrupted", exception);
        }
    }

    private static GuardianArticle toArticle(JsonNode result) {
        JsonNode fields = result.path("fields");
        String publicationDate = result.path("webPublicationDate").asText("");
        return new GuardianArticle(
                result.path("id").asText(""),
                result.path("sectionId").asText(""),
                result.path("sectionName").asText(""),
                result.path("webTitle").asText(""),
                result.path("webUrl").asText(""),
                publicationDate.isBlank() ? Instant.EPOCH : Instant.parse(publicationDate),
                fields.path("headline").asText(""),
                fields.path("trailText").asText(""),
                fields.path("byline").asText(""),
                fields.path("publication").asText("")
        );
    }
}
