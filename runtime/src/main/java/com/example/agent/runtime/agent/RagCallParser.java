package com.example.agent.runtime.agent;

import java.util.Optional;

final class RagCallParser {
    private RagCallParser() {
    }

    static Optional<RagCall> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] lines = text.strip().split("\\R");
        String first = lines[0].trim();
        if (!first.regionMatches(true, 0, "RAG:", 0, "RAG:".length())) {
            return Optional.empty();
        }
        String query = first.substring("RAG:".length()).trim();
        int topK = 3;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.regionMatches(true, 0, "query:", 0, "query:".length())) {
                query = line.substring("query:".length()).trim();
            } else if (line.regionMatches(true, 0, "topK:", 0, "topK:".length())) {
                topK = parseTopK(line.substring("topK:".length()).trim(), topK);
            }
        }
        return query.isBlank() ? Optional.empty() : Optional.of(new RagCall(query, topK));
    }

    private static int parseTopK(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record RagCall(String query, int topK) {
        RagCall {
            query = query == null ? "" : query;
            topK = Math.max(1, topK);
        }
    }
}
