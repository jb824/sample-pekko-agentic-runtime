package com.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

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

public final class WebSearchToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "web.search";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_QUERY_LENGTH = 180;

    private final HttpClient httpClient;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(WebSearchToolActor::new);
    }

    private WebSearchToolActor(ActorContext<ToolProtocol.Command> context) {
        super(context);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        getContext().getLog().info("Tool {} invoked for request {}", command.toolName(), command.requestId());
        String query = normalizeQuery(command.arguments().getOrDefault("query", ""));
        int maxResults = parseMaxResults(command.arguments().get("maxResults"));
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

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        command.replyTo().tell(failed(command, failure));
                    } else if (response == null) {
                        command.replyTo().tell(failed(
                                command,
                                new IllegalStateException("web search timed out after " + REQUEST_TIMEOUT)
                        ));
                    } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        command.replyTo().tell(failed(
                                command,
                                new IllegalStateException("web search returned HTTP " + response.statusCode())
                        ));
                    } else {
                        ToolProtocol.ToolResult result = succeeded(command, response.body(), maxResults);
                        getContext().getLog().info(
                                "Tool {} completed for request {}: {}",
                                command.toolName(),
                                command.requestId(),
                                resourceSummary(result.output())
                        );
                        command.replyTo().tell(result);
                    }
                });

        return this;
    }

    private static ToolProtocol.ToolResult succeeded(
            ToolProtocol.InvokeTool command,
            String body,
            int maxResults
    ) {
        try {
            JsonNode root = JSON.readTree(body);
            List<Resource> resources = new ArrayList<>();
            String abstractText = root.path("AbstractText").asText("");
            String abstractUrl = root.path("AbstractURL").asText("");
            if (!abstractText.isBlank()) {
                String heading = root.path("Heading").asText("DuckDuckGo summary");
                resources.add(new Resource(heading, abstractText, abstractUrl));
            }
            collectResults(root.path("Results"), resources, maxResults);
            collectRelatedTopics(root.path("RelatedTopics"), resources, maxResults);
            String output = resources.isEmpty() ? "No web search results returned." : format(resources);
            return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), output, null);
        } catch (Exception exception) {
            return failed(command, exception);
        }
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

    private static String format(List<Resource> resources) {
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            lines.add((i + 1) + ". " + resource.title());
            lines.add("   URL: " + blankToUnknown(resource.url()));
            lines.add("   Snippet: " + resource.snippet());
        }
        return String.join("\n", lines);
    }

    private static String resourceSummary(String output) {
        List<String> urls = output.lines()
                .filter(line -> line.trim().startsWith("URL:"))
                .map(line -> line.trim().substring("URL:".length()).trim())
                .filter(url -> !url.equals("<unknown>"))
                .toList();
        return "resources=" + urls.size() + " urls=" + urls;
    }

    private static String titleFromText(String text) {
        int separator = text.indexOf(" - ");
        if (separator > 0) {
            return text.substring(0, separator).trim();
        }
        return text.length() > 80 ? text.substring(0, 80).trim() + "..." : text;
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return 3;
        }
        return Math.max(1, Integer.parseInt(value));
    }

    private static String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_QUERY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_QUERY_LENGTH).trim();
    }

    private static ToolProtocol.ToolResult failed(ToolProtocol.InvokeTool command, Throwable error) {
        return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "", error);
    }

    private record Resource(String title, String snippet, String url) {
    }
}
