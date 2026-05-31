package com.example.agent.tool.service;

import com.example.agent.tool.adapter.PubMedSearchAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PubMedSearchToolService implements ToolService {
    private static final int MAX_QUERY_LENGTH = 220;
    private final PubMedSearchAdapter adapter;

    public PubMedSearchToolService(PubMedSearchAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        String query = normalizeQuery(arguments.getOrDefault("query", ""));
        int maxResults = ToolArgumentParser.parseBoundedPositiveInt(arguments.get("maxResults"), 3, 10);
        List<PubMedSearchAdapter.Resource> resources = adapter.search(query, maxResults);
        return resources.isEmpty() ? "No PubMed results returned." : format(resources);
    }

    private static String format(List<PubMedSearchAdapter.Resource> resources) {
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < resources.size(); i++) {
            PubMedSearchAdapter.Resource resource = resources.get(i);
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

    private static String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_QUERY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_QUERY_LENGTH).trim();
    }
}
