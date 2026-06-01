package com.example.agent.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

public final class ToolCatalog {
    public static final String TIME_NOW = "time.now";
    public static final String WEB_SEARCH = "web.search";
    public static final String ARXIV_SEARCH = "arxiv.search";
    public static final String PUBMED_SEARCH = "pubmed.search";

    private ToolCatalog() {
    }

    public static List<String> parseEnabledTools(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(toolName -> toolName.toLowerCase().replace("\"", "").replace("[", "").replace("]", ""))
                .filter(toolName -> !toolName.isBlank())
                .filter(toolName -> !"none".equals(toolName))
                .filter(toolName -> !"null".equals(toolName))
                .filter(ToolCatalog::isKnownTool)
                .collect(Collectors.toList());
    }

    public static String promptDescription(String toolName) {
        return switch (toolName) {
            case TIME_NOW -> """
                    - time.now
                      Description: Gets the current time for a requested time zone.
                      Inputs: zone=<IANA time zone such as UTC or America/Toronto>; defaults to UTC.
                      Output: one ISO-8601 offset timestamp string, for example 2026-05-29T16:18:38.438794317Z.
                    """.stripTrailing();
            case WEB_SEARCH -> """
                    - web.search
                      Description: Searches DuckDuckGo Instant Answer for current web facts and source URLs. Use as a broad fallback, not as the preferred biomedical literature tool.
                      Inputs: query=<short web search query>; maxResults=<positive integer>; defaults to 3.
                      Output: Resources list with Title, URL, and Snippet fields, or "No web search results returned."
                    """.stripTrailing();
            case ARXIV_SEARCH -> """
                    - arxiv.search
                      Description: Searches arXiv Atom API for preprints. Use for ML, CS, physics, quantitative methods, and other arXiv-heavy domains; do not prefer it for clinical treatment evidence.
                      Inputs: query=<short paper search query>; maxResults=<positive integer>; defaults to 3.
                      Output: Resources list with paper Title, URL, and Snippet fields, or "No arXiv results returned."
                    """.stripTrailing();
            case PUBMED_SEARCH -> """
                    - pubmed.search
                      Description: Searches PubMed for biomedical and clinical literature. Prefer this for diseases, treatments, cancer, drugs, trials, and other medical questions.
                      Inputs: query=<short biomedical literature query>; maxResults=<positive integer>; defaults to 3.
                      Output: Resources list with Title, URL, PMID, Journal, Year, and Snippet fields, or "No PubMed results returned."
                    """.stripTrailing();
            default -> "- " + toolName + "\n  Description: Unknown tool.";
        };
    }

    public static String promptDescriptions(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "- none";
        }
        return toolNames.stream()
                .map(ToolCatalog::promptDescription)
                .collect(Collectors.joining("\n"));
    }

    public static boolean isKnownTool(String toolName) {
        return Set.of(
                TIME_NOW,
                WEB_SEARCH,
                ARXIV_SEARCH,
                PUBMED_SEARCH
        ).contains(toolName);
    }

    public static boolean isSourceTool(String toolName) {
        return WEB_SEARCH.equals(toolName) || ARXIV_SEARCH.equals(toolName) || PUBMED_SEARCH.equals(toolName);
    }

    public static Map<String, String> defaultArguments(String toolName, String fallbackQuery, String requestedQuery) {
        String query = requestedQuery == null || requestedQuery.isBlank() ? fallbackQuery : requestedQuery;
        return switch (toolName) {
            case TIME_NOW -> Map.of(
                    "zone",
                    requestedQuery == null || requestedQuery.isBlank() ? "UTC" : requestedQuery
            );
            case WEB_SEARCH, ARXIV_SEARCH, PUBMED_SEARCH -> Map.of(
                    "query",
                    query == null ? "" : query,
                    "maxResults",
                    "3"
            );
            default -> Map.of();
        };
    }
}
