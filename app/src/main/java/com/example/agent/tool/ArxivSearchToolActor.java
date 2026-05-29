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
import java.util.concurrent.TimeUnit;

public final class ArxivSearchToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "arxiv.search";
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_QUERY_TERMS = 8;

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
        int maxResults = parseMaxResults(command.arguments().get("maxResults"));
        String encodedQuery = URLEncoder.encode("all:" + query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://export.arxiv.org/api/query?search_query=" + encodedQuery
                + "&start=0&max_results=" + maxResults);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/atom+xml, application/xml, text/xml")
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

    private static ToolProtocol.ToolResult succeeded(
            ToolProtocol.InvokeTool command,
            String body,
            int maxResults
    ) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
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
        return Math.max(1, Integer.parseInt(value));
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

    private record Resource(String title, String snippet, String url) {
    }
}
