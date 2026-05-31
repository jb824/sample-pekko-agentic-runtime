package com.example.agent.tool.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class PubMedSearchAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    public List<Resource> search(String query, int maxResults) {
        List<String> pmids = searchIds(query, maxResults);
        if (pmids.isEmpty()) {
            return List.of();
        }
        return fetchSummaries(pmids);
    }

    private List<String> searchIds(String query, int maxResults) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                + "?db=pubmed&retmode=json&retmax=" + maxResults + "&term=" + encodedQuery);
        HttpBody body = get(uri, "application/json");
        return parseIds(body.body());
    }

    private List<Resource> fetchSummaries(List<String> pmids) {
        String ids = String.join(",", pmids);
        URI uri = URI.create("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                + "?db=pubmed&retmode=json&id=" + URLEncoder.encode(ids, StandardCharsets.UTF_8));
        HttpBody body = get(uri, "application/json");
        return parseSummaries(body.body());
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

    private static List<Resource> parseSummaries(String body) {
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
            return resources;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse PubMed summary response", exception);
        }
    }

    public record Resource(String title, String url, String pmid, String journal, String year, String snippet) {
    }

    private record HttpBody(int statusCode, String body) {
    }
}
