package com.example.agent.tool.adapter;

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

public final class ArxivSearchAdapter {
    private static final String USER_AGENT = "pekko-agent-runtime/0.1 (+https://localhost)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private final HttpClient httpClient;

    public ArxivSearchAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<Resource> search(String query, int maxResults) throws Exception {
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

        HttpResponse<String> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
                .join();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("arXiv search returned HTTP " + response.statusCode());
        }
        return parseResources(response.body(), maxResults);
    }

    private static List<Resource> parseResources(String body, int maxResults) throws Exception {
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
        return resources;
    }

    private static String text(Element entry, String tagName) {
        NodeList nodes = entry.getElementsByTagNameNS("*", tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    public record Resource(String title, String snippet, String url) {
    }
}
