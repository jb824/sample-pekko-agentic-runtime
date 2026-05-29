package com.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PubMedSearchToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "pubmed.search";

    private static final Logger LOGGER = LoggerFactory.getLogger(PubMedSearchToolActor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_QUERY_LENGTH = 220;
    private final ExecutorService executor;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(PubMedSearchToolActor::new);
    }

    private PubMedSearchToolActor(ActorContext<ToolProtocol.Command> context) {
        super(context);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("pubmed-tool-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<ToolProtocol.Command> onPostStop() {
        executor.shutdownNow();
        return Behaviors.same();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        getContext().getLog().info("Tool {} invoked for request {}", command.toolName(), command.requestId());
        String query = normalizeQuery(command.arguments().getOrDefault("query", ""));
        int maxResults = parseMaxResults(command.arguments().get("maxResults"));
        getContext().getLog().info(
                "Tool {} normalized query for request {}: query='{}' maxResults={}",
                command.toolName(),
                command.requestId(),
                query,
                maxResults
        );

        executor.execute(() -> runSearch(command, query, maxResults));

        return this;
    }

    private void runSearch(ToolProtocol.InvokeTool command, String query, int maxResults) {
        try {
            List<String> pmids = searchIds(query, maxResults);
            ToolProtocol.ToolResult result = pmids.isEmpty()
                    ? new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "No PubMed results returned.", null)
                    : fetchSummaries(command, pmids);
            LOGGER.info(
                    "Tool {} completed for request {}: {}",
                    command.toolName(),
                    command.requestId(),
                    resourceSummary(result.output())
            );
            command.replyTo().tell(result);
        } catch (Throwable failure) {
            Throwable root = rootCause(failure);
            LOGGER.warn(
                    "Tool {} failed for request {}: {}: {}",
                    command.toolName(),
                    command.requestId(),
                    root.getClass().getSimpleName(),
                    root.getMessage()
            );
            command.replyTo().tell(failed(command, failure));
        }
    }

    private List<String> searchIds(String query, int maxResults) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                + "?db=pubmed&retmode=json&retmax=" + maxResults + "&term=" + encodedQuery);
        LOGGER.info("PubMed esearch started: {}", uri);
        HttpBody body = get(uri, "application/json");
        LOGGER.info("PubMed esearch completed: status={}", body.statusCode());
        return parseIds(body.body());
    }

    private ToolProtocol.ToolResult fetchSummaries(
            ToolProtocol.InvokeTool command,
            List<String> pmids
    ) {
        String ids = String.join(",", pmids);
        URI uri = URI.create("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                + "?db=pubmed&retmode=json&id=" + URLEncoder.encode(ids, StandardCharsets.UTF_8));
        LOGGER.info("PubMed esummary started: ids={}", ids);
        HttpBody body = get(uri, "application/json");
        LOGGER.info("PubMed esummary completed: status={} ids={}", body.statusCode(), ids);
        return new ToolProtocol.ToolResult(
                command.requestId(),
                command.toolName(),
                formatSummaries(body.body()),
                null
        );
    }

    private static HttpBody get(URI uri, String accept) {
        try {
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) REQUEST_TIMEOUT.toMillis());
            connection.setReadTimeout((int) REQUEST_TIMEOUT.toMillis());
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", accept);
            int statusCode = connection.getResponseCode();
            String body = new String(
                    (statusCode >= 200 && statusCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream()).readAllBytes(),
                    StandardCharsets.UTF_8
            );
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("PubMed request returned HTTP " + statusCode + ": " + body);
            }
            return new HttpBody(statusCode, body);
        } catch (Exception exception) {
            throw new IllegalStateException("PubMed request failed: " + uri, exception);
        }
    }

    private static List<String> parseIds(String body) {
        try {
            JsonNode ids = JSON.readTree(body).path("esearchresult").path("idlist");
            List<String> pmids = new ArrayList<>();
            if (ids.isArray()) {
                ids.forEach(id -> {
                    String pmid = id.asText("");
                    if (!pmid.isBlank()) {
                        pmids.add(pmid);
                    }
                });
            }
            return pmids;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse PubMed search response", exception);
        }
    }

    private static String formatSummaries(String body) {
        try {
            JsonNode result = JSON.readTree(body).path("result");
            JsonNode uids = result.path("uids");
            List<Resource> resources = new ArrayList<>();
            if (uids.isArray()) {
                for (JsonNode uid : uids) {
                    String pmid = uid.asText("");
                    JsonNode summary = result.path(pmid);
                    String title = summary.path("title").asText("").replaceAll("\\s+", " ").trim();
                    String journal = summary.path("fulljournalname").asText("").replaceAll("\\s+", " ").trim();
                    String pubDate = summary.path("pubdate").asText("").trim();
                    String year = pubDate.length() >= 4 ? pubDate.substring(0, 4) : "";
                    String source = summary.path("source").asText("").trim();
                    String snippet = source.isBlank() ? pubDate : source + (pubDate.isBlank() ? "" : ", " + pubDate);
                    if (!pmid.isBlank() && !title.isBlank()) {
                        resources.add(new Resource(
                                title,
                                "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/",
                                pmid,
                                journal,
                                year,
                                snippet
                        ));
                    }
                }
            }
            return resources.isEmpty() ? "No PubMed results returned." : format(resources);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse PubMed summary response", exception);
        }
    }

    private static String format(List<Resource> resources) {
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            lines.add((i + 1) + ". " + resource.title());
            lines.add("   URL: " + resource.url());
            lines.add("   PMID: " + resource.pmid());
            if (!resource.journal().isBlank()) {
                lines.add("   Journal: " + resource.journal());
            }
            if (!resource.year().isBlank()) {
                lines.add("   Year: " + resource.year());
            }
            if (!resource.snippet().isBlank()) {
                lines.add("   Snippet: " + resource.snippet());
            }
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

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record Resource(String title, String url, String pmid, String journal, String year, String snippet) {
    }

    private record HttpBody(int statusCode, String body) {
    }
}
