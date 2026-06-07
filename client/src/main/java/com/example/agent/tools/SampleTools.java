package com.example.agent.tools;

import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolRequest;
import com.example.agent.api.AgentToolResult;
import com.example.agent.config.AppConfig;
import com.example.agent.rag.HttpRagRetrievalClient;
import com.example.agent.rag.RagDocument;
import com.example.agent.rag.RagRetrieveRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SampleTools {
    public static final String TIME_NOW = "time.now";
    public static final String WEB_SEARCH = "web.search";
    public static final String ARXIV_SEARCH = "arxiv.search";
    public static final String RAG_RETRIEVE = "rag.retrieve";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SampleTools() {
    }


    public static AgentToolDefinition definition(String toolName, AppConfig config) {
        return switch (toolName) {
            case TIME_NOW -> timeNow(Clock.systemUTC());
            case WEB_SEARCH -> webSearch();
            case ARXIV_SEARCH -> arxivSearch();
            case RAG_RETRIEVE -> ragRetrieve(config);
            default -> throw new IllegalArgumentException("Unknown sample tool: " + toolName);
        };
    }

    public static AgentToolDefinition timeNow(Clock clock) {
        return AgentToolDefinition.named(TIME_NOW)
                .describedAs("Gets the current date and time for a given IANA time zone. Required argument: zone (e.g. UTC, Europe/London, America/New_York, Asia/Tokyo). If the user has not specified a time zone, ask them before calling this tool.")
                .timeout(Duration.ofSeconds(5))
                .handledBy(request -> CompletableFuture.completedFuture(currentTime(clock, request)))
                .build();
    }

    public static AgentToolDefinition webSearch() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return AgentToolDefinition.named(WEB_SEARCH)
                .describedAs("Searches DuckDuckGo Instant Answer for current web facts and source URLs.")
                .sourceCapable(true)
                .timeout(Duration.ofSeconds(8))
                .handledBy(request -> searchWeb(httpClient, request))
                .build();
    }

    public static AgentToolDefinition arxivSearch() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return AgentToolDefinition.named(ARXIV_SEARCH)
                .describedAs("Searches arXiv Atom API for preprints.")
                .sourceCapable(true)
                .timeout(Duration.ofSeconds(45))
                .handledBy(request -> searchArxiv(httpClient, request))
                .build();
    }

    public static AgentToolDefinition ragRetrieve(AppConfig config) {
        HttpRagRetrievalClient client = new HttpRagRetrievalClient(
                config.ragRetrievalUrl(),
                Duration.ofMillis(config.ragServiceTimeoutMs()),
                config.ragRetrievalApiKey()
        );
        return AgentToolDefinition.named(RAG_RETRIEVE)
                .describedAs("Searches tenant-scoped vector knowledge for grounded context and citations.")
                .sourceCapable(true)
                .timeout(Duration.ofSeconds(10))
                .handledBy(request -> {
                    RagRetrieveRequest ragRequest = new RagRetrieveRequest(
                            request.tenantId(),
                            request.userInput(),
                            5,
                            config.ragCollection(),
                            Map.of()
                    );
                    return client.retrieve(ragRequest)
                            .thenApply(response -> formatRag(response.documents()))
                            .exceptionally(AgentToolResult::failure);
                })
                .build();
    }

    private static AgentToolResult currentTime(Clock clock, AgentToolRequest request) {
        String zone = request.arguments().get("zone");
        if (zone == null || zone.isBlank()) {
            return AgentToolResult.failure(new IllegalArgumentException(
                    "Required argument 'zone' is missing. Ask the user for their time zone, or use one of: "
                    + "UTC, Europe/London, America/New_York, America/Chicago, America/Denver, America/Los_Angeles, "
                    + "America/Sao_Paulo, Africa/Johannesburg, Asia/Dubai, Asia/Kolkata, Asia/Tokyo, Australia/Sydney."));
        }
        try {
            ZoneId zoneId = ZoneId.of(zone);
            Instant now = clock.instant();
            return AgentToolResult.success(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zoneId)));
        } catch (java.time.zone.ZoneRulesException exception) {
            return AgentToolResult.failure(new IllegalArgumentException(
                    "Unknown time zone '" + zone + "'. Use an IANA zone ID such as: "
                    + "UTC, Europe/London, America/New_York, Asia/Tokyo, Australia/Sydney. "
                    + "Full list: https://en.wikipedia.org/wiki/List_of_tz_database_time_zones"));
        } catch (RuntimeException exception) {
            return AgentToolResult.failure(exception);
        }
    }

    private static CompletableFuture<AgentToolResult> searchWeb(HttpClient httpClient, AgentToolRequest request) {
        String query = normalizeQuery(request.userInput(), 180);
        URI uri = URI.create("https://api.duckduckgo.com/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&no_html=1&skip_disambig=1");
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "pekko-agent-runtime-sample/0.1 (+https://localhost)")
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .orTimeout(13, TimeUnit.SECONDS)
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return AgentToolResult.failure(new IllegalStateException("web search returned HTTP " + response.statusCode()));
                    }
                    return formatWeb(response.body());
                })
                .exceptionally(AgentToolResult::failure);
    }

    private static AgentToolResult formatWeb(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            List<Resource> resources = new ArrayList<>();
            String abstractText = root.path("AbstractText").asText("");
            String abstractUrl = root.path("AbstractURL").asText("");
            if (!abstractText.isBlank()) {
                resources.add(new Resource(root.path("Heading").asText("DuckDuckGo summary"), abstractText, abstractUrl));
            }
            collectWeb(root.path("Results"), resources, 3);
            collectWeb(root.path("RelatedTopics"), resources, 3);
            return formatResources(resources, "No web search results returned.");
        } catch (Exception exception) {
            return AgentToolResult.failure(exception);
        }
    }

    private static CompletableFuture<AgentToolResult> searchArxiv(HttpClient httpClient, AgentToolRequest request) {
        String query = normalizeQuery(request.userInput(), 180);
        URI uri = URI.create("https://export.arxiv.org/api/query?search_query="
                + URLEncoder.encode("all:" + query, StandardCharsets.UTF_8)
                + "&start=0&max_results=3&sortBy=lastUpdatedDate&sortOrder=descending");
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "pekko-agent-runtime-sample/0.1 (+https://localhost)")
                .header("Accept", "application/atom+xml, application/xml, text/xml")
                .GET()
                .build();
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .orTimeout(50, TimeUnit.SECONDS)
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return AgentToolResult.failure(new IllegalStateException("arXiv search returned HTTP " + response.statusCode()));
                    }
                    return formatArxiv(response.body());
                })
                .exceptionally(AgentToolResult::failure);
    }

    private static AgentToolResult formatArxiv(String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setNamespaceAware(true);
            Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body))).getDocumentElement();
            NodeList entries = root.getElementsByTagNameNS("*", "entry");
            List<Resource> resources = new ArrayList<>();
            for (int index = 0; index < entries.getLength() && resources.size() < 3; index++) {
                Element entry = (Element) entries.item(index);
                resources.add(new Resource(
                        text(entry, "title").replaceAll("\\s+", " ").trim(),
                        truncate(text(entry, "summary").replaceAll("\\s+", " ").trim(), 220),
                        text(entry, "id").trim()
                ));
            }
            return formatResources(resources, "No arXiv results returned.");
        } catch (Exception exception) {
            return AgentToolResult.failure(exception);
        }
    }

    private static AgentToolResult formatRag(List<RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return AgentToolResult.success("No RAG results returned.");
        }
        List<Resource> resources = documents.stream()
                .map(document -> new Resource(
                        blankSafe(document.title(), "Untitled"),
                        blankSafe(document.snippet(), ""),
                        blankSafe(document.reference(), "<unknown>")
                ))
                .toList();
        return formatResources(resources, "No RAG results returned.");
    }

    private static AgentToolResult formatResources(List<Resource> resources, String emptyMessage) {
        if (resources.isEmpty()) {
            return AgentToolResult.success(emptyMessage);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int index = 0; index < resources.size(); index++) {
            Resource resource = resources.get(index);
            lines.add((index + 1) + ". " + resource.title());
            lines.add("   URL: " + blankSafe(resource.url(), "<unknown>"));
            lines.add("   Snippet: " + resource.snippet());
        }
        return AgentToolResult.success(
                String.join("\n", lines),
                resources.stream()
                        .map(Resource::url)
                        .filter(url -> url != null && !url.isBlank() && !"<unknown>".equals(url))
                        .toList()
        );
    }

    private static void collectWeb(JsonNode nodes, List<Resource> resources, int maxResults) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            if (resources.size() >= maxResults) {
                return;
            }
            if (node.has("Topics")) {
                collectWeb(node.path("Topics"), resources, maxResults);
            } else {
                String text = node.path("Text").asText("");
                String url = node.path("FirstURL").asText("");
                if (!text.isBlank()) {
                    resources.add(new Resource(truncate(text, 80), text, url));
                }
            }
        }
    }

    private static String text(Element entry, String tagName) {
        NodeList nodes = entry.getElementsByTagNameNS("*", tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static String normalizeQuery(String query, int maxLength) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength).trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private static String blankSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Resource(String title, String snippet, String url) {
    }
}
