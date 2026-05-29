package com.example.agent.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ToolCatalog {
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
            case TimeToolActor.TOOL_NAME -> """
                    - time.now
                      Description: Gets the current time for a requested time zone.
                      Inputs: zone=<IANA time zone such as UTC or America/Toronto>; defaults to UTC.
                      Output: one ISO-8601 offset timestamp string, for example 2026-05-29T16:18:38.438794317Z.
                    """.stripTrailing();
            case WebSearchToolActor.TOOL_NAME -> """
                    - web.search
                      Description: Searches DuckDuckGo Instant Answer for current web facts and source URLs. Use as a broad fallback, not as the preferred biomedical literature tool.
                      Inputs: query=<short web search query>; maxResults=<positive integer>; defaults to 3.
                      Output: Resources list with Title, URL, and Snippet fields, or "No web search results returned."
                    """.stripTrailing();
            case ArxivSearchToolActor.TOOL_NAME -> """
                    - arxiv.search
                      Description: Searches arXiv Atom API for preprints. Use for ML, CS, physics, quantitative methods, and other arXiv-heavy domains; do not prefer it for clinical treatment evidence.
                      Inputs: query=<short paper search query>; maxResults=<positive integer>; defaults to 3.
                      Output: Resources list with paper Title, URL, and Snippet fields, or "No arXiv results returned."
                    """.stripTrailing();
            case PubMedSearchToolActor.TOOL_NAME -> """
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
                TimeToolActor.TOOL_NAME,
                WebSearchToolActor.TOOL_NAME,
                ArxivSearchToolActor.TOOL_NAME,
                PubMedSearchToolActor.TOOL_NAME
        ).contains(toolName);
    }
}
