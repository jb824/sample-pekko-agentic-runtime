package com.example.agent.tool;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@AgentTool(name = ToolCatalog.ARXIV_SEARCH, sourceCapable = true, timeoutSeconds = 45, retryAttempts = 2)
public final class ArxivSearchToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "arxiv.search";
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration REQUEST_SPACING = Duration.ofSeconds(3);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_QUERY_TERMS = 8;
    private static final int MAX_RESULTS_CAP = 25;
    private static final AtomicLong NEXT_ALLOWED_REQUEST_MILLIS = new AtomicLong(0L);

    private final HttpClient httpClient;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(ArxivSearchToolActor::new);
    }

    private ArxivSearchToolActor(ActorContext<ToolProtocol.Command> context) {
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
        if (query.isBlank()) {
            command.replyTo().tell(failed(command, new IllegalArgumentException("arXiv query must not be blank")));
            return this;
        }
        int maxResults = parseMaxResults(command.arguments().get("maxResults"));
        String encodedQuery = URLEncoder.encode("all:" + query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://export.arxiv.org/api/query?search_query=" + encodedQuery
                + "&start=0&max_results=" + maxResults
                + "&sortBy=lastUpdatedDate&sortOrder=descending");

        sendWithRetry(uri, 1)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        command.replyTo().tell(failed(command, failure));
                    } else if (response == null) {
                        command.replyTo().tell(failed(
                                command,
                                new IllegalStateException("arXiv search timed out after " + REQUEST_TIMEOUT)
                        ));
                    } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        command.replyTo().tell(failed(
                                command,
                                new IllegalStateException("arXiv search returned HTTP " + response.statusCode())
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

    private CompletableFuture<HttpResponse<String>> sendWithRetry(URI uri, int attempt) {
        return sendRespectingRateLimit(uri)
                .thenCompose(response -> {
                    if (isSuccess(response.statusCode())) {
                        return CompletableFuture.completedFuture(response);
                    }
                    if (attempt >= MAX_ATTEMPTS || !isRetryableStatus(response.statusCode())) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("arXiv search returned HTTP " + response.statusCode())
                        );
                    }
                    long backoffMs = backoffMillis(attempt);
                    return CompletableFuture.supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS)
                    ).thenCompose(ignored -> sendWithRetry(uri, attempt + 1));
                })
                .exceptionallyCompose(failure -> {
                    if (attempt >= MAX_ATTEMPTS) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    long backoffMs = backoffMillis(attempt);
                    return CompletableFuture.supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS)
                    ).thenCompose(ignored -> sendWithRetry(uri, attempt + 1));
                });
    }

    private CompletableFuture<HttpResponse<String>> sendRespectingRateLimit(URI uri) {
        long now = System.currentTimeMillis();
        long reserved = NEXT_ALLOWED_REQUEST_MILLIS.updateAndGet(previous -> Math.max(previous, now) + REQUEST_SPACING.toMillis());
        long delayMs = Math.max(0L, reserved - REQUEST_SPACING.toMillis() - now);

        return CompletableFuture.supplyAsync(
                () -> null,
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
        ).thenCompose(ignored -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/atom+xml, application/xml, text/xml")
                    .GET()
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    private static ToolProtocol.ToolResult succeeded(
            ToolProtocol.InvokeTool command,
            String body,
            int maxResults
    ) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            NodeList entries = document.getElementsByTagNameNS("*", "entry");
            List<Resource> resources = new ArrayList<>();
            for (int i = 0; i < entries.getLength() && resources.size() < maxResults; i++) {
                Element entry = (Element) entries.item(i);
                String title = text(entry, "title").replaceAll("\\s+", " ").trim();
                String id = text(entry, "id").trim();
                String summary = text(entry, "summary").replaceAll("\\s+", " ").trim();
                if (summary.length() > 220) {
                    summary = summary.substring(0, 220) + "...";
                }
                resources.add(new Resource(title, summary, id));
            }
            String output = resources.isEmpty() ? "No arXiv results returned." : format(resources);
            return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), output, null);
        } catch (Exception exception) {
            return failed(command, exception);
        }
    }

    private static String format(List<Resource> resources) {
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            lines.add((i + 1) + ". " + resource.title());
            lines.add("   URL: " + resource.url());
            lines.add("   Snippet: " + resource.snippet());
        }
        return String.join("\n", lines);
    }

    private static String resourceSummary(String output) {
        List<String> urls = output.lines()
                .filter(line -> line.trim().startsWith("URL:"))
                .map(line -> line.trim().substring("URL:".length()).trim())
                .toList();
        return "resources=" + urls.size() + " urls=" + urls;
    }

    private static String text(Element entry, String tagName) {
        NodeList nodes = entry.getElementsByTagNameNS("*", tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return 3;
        }
        return Math.min(MAX_RESULTS_CAP, Math.max(1, Integer.parseInt(value)));
    }

    private static String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] terms = normalized.split(" ");
        List<String> selected = new ArrayList<>();
        for (String term : terms) {
            if (selected.size() >= MAX_QUERY_TERMS) {
                break;
            }
            if (term.length() >= 3 && !isStopWord(term)) {
                selected.add(term);
            }
        }
        return selected.isEmpty() ? normalized : String.join(" ", selected);
    }

    private static boolean isStopWord(String term) {
        return List.of(
                "what", "when", "where", "which", "with", "from", "that", "this",
                "about", "include", "related", "available", "recent", "latest",
                "cause", "causes", "leading"
        ).contains(term);
    }

    private static ToolProtocol.ToolResult failed(ToolProtocol.InvokeTool command, Throwable error) {
        return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "", error);
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode == 500
                || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static long backoffMillis(int attempt) {
        return switch (attempt) {
            case 1 -> 800L;
            case 2 -> 1600L;
            default -> 2400L;
        };
    }

    private record Resource(String title, String snippet, String url) {
    }
}
