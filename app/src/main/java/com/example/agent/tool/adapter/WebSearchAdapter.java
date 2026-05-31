package com.example.agent.tool.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class WebSearchAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;

    public WebSearchAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<Resource> search(String query, int maxResults) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.duckduckgo.com/?q=" + encodedQuery
                + "&format=json&no_html=1&skip_disambig=1");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
                .join();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("web search returned HTTP " + response.statusCode());
        }

        JsonNode root = JSON.readTree(response.body());
        List<Resource> resources = new ArrayList<>();
        String abstractText = root.path("AbstractText").asText("");
        String abstractUrl = root.path("AbstractURL").asText("");
        if (!abstractText.isBlank()) {
            String heading = root.path("Heading").asText("DuckDuckGo summary");
            resources.add(new Resource(heading, abstractText, abstractUrl));
        }
        collectResults(root.path("Results"), resources, maxResults);
        collectRelatedTopics(root.path("RelatedTopics"), resources, maxResults);
        return resources;
    }

    private static void collectRelatedTopics(JsonNode topics, List<Resource> resources, int maxResults) {
        if (!topics.isArray()) {
            return;
        }
        for (JsonNode topic : topics) {
            if (resources.size() >= maxResults) {
                return;
            }
            if (topic.has("Topics")) {
                collectRelatedTopics(topic.path("Topics"), resources, maxResults);
            } else {
                String text = topic.path("Text").asText("");
                String url = topic.path("FirstURL").asText("");
                if (!text.isBlank()) {
                    resources.add(new Resource(titleFromText(text), text, url));
                }
            }
        }
    }

    private static void collectResults(JsonNode results, List<Resource> resources, int maxResults) {
        if (!results.isArray()) {
            return;
        }
        for (JsonNode result : results) {
            if (resources.size() >= maxResults) {
                return;
            }
            String text = result.path("Text").asText("");
            String url = result.path("FirstURL").asText("");
            if (!text.isBlank()) {
                resources.add(new Resource(titleFromText(text), text, url));
            }
        }
    }

    private static String titleFromText(String text) {
        int separator = text.indexOf(" - ");
        if (separator > 0) {
            return text.substring(0, separator).trim();
        }
        return text.length() > 80 ? text.substring(0, 80).trim() + "..." : text;
    }

    public record Resource(String title, String snippet, String url) {
    }
}
