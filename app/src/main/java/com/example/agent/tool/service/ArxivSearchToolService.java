package com.example.agent.tool.service;

import com.example.agent.tool.adapter.ArxivSearchAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ArxivSearchToolService implements ToolService {
    private static final int MAX_QUERY_TERMS = 8;
    private final ArxivSearchAdapter adapter;

    public ArxivSearchToolService(ArxivSearchAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String execute(Map<String, String> arguments) throws Exception {
        String query = normalizeQuery(arguments.getOrDefault("query", ""));
        int maxResults = ToolArgumentParser.parseBoundedPositiveInt(arguments.get("maxResults"), 3, 10);
        List<ArxivSearchAdapter.Resource> resources = adapter.search(query, maxResults);
        return resources.isEmpty() ? "No arXiv results returned." : format(resources);
    }

    private static String format(List<ArxivSearchAdapter.Resource> resources) {
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < resources.size(); i++) {
            ArxivSearchAdapter.Resource resource = resources.get(i);
            lines.add((i + 1) + ". " + resource.title());
            lines.add("   URL: " + resource.url());
            lines.add("   Snippet: " + resource.snippet());
        }
        return String.join("\n", lines);
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
}
