package com.example.agent.tool.service;

import com.example.agent.tool.adapter.WebSearchAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WebSearchToolService implements ToolService {
    private static final int MAX_QUERY_LENGTH = 180;
    private final WebSearchAdapter adapter;

    public WebSearchToolService(WebSearchAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String execute(Map<String, String> arguments) throws Exception {
        String query = normalizeQuery(arguments.getOrDefault("query", ""));
        int maxResults = ToolArgumentParser.parseBoundedPositiveInt(arguments.get("maxResults"), 3, 10);
        List<WebSearchAdapter.Resource> resources = adapter.search(query, maxResults);
        return resources.isEmpty() ? "No web search results returned." : format(resources);
    }

    private static String format(List<WebSearchAdapter.Resource> resources) {
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < resources.size(); i++) {
            WebSearchAdapter.Resource resource = resources.get(i);
            lines.add((i + 1) + ". " + resource.title());
            lines.add("   URL: " + blankToUnknown(resource.url()));
            lines.add("   Snippet: " + resource.snippet());
        }
        return String.join("\n", lines);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }

    private static String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_QUERY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_QUERY_LENGTH).trim();
    }
}
