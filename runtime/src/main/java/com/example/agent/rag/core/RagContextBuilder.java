package com.example.agent.rag.core;

import java.util.ArrayList;
import java.util.List;

public final class RagContextBuilder {
    private final int maxContextChars;

    public RagContextBuilder(int maxContextChars) {
        this.maxContextChars = Math.max(0, maxContextChars);
    }

    public String build(RagRetrievalResult result) {
        if (result == null || result.chunks().isEmpty() || maxContextChars == 0) {
            return "None.";
        }
        List<String> blocks = new ArrayList<>();
        int used = 0;
        for (int index = 0; index < result.chunks().size(); index++) {
            RetrievedChunk chunk = result.chunks().get(index);
            String block = format(index + 1, chunk);
            if (used + block.length() > maxContextChars) {
                int remaining = maxContextChars - used;
                if (remaining <= 80) {
                    break;
                }
                block = block.substring(0, remaining) + "...";
            }
            blocks.add(block);
            used += block.length();
            if (used >= maxContextChars) {
                break;
            }
        }
        return blocks.isEmpty() ? "None." : String.join("\n\n", blocks);
    }

    private static String format(int index, RetrievedChunk chunk) {
        RagChunkMetadata metadata = chunk.metadata();
        StringBuilder builder = new StringBuilder();
        builder.append("[").append(index).append("] ");
        builder.append(blank(metadata.title(), "Untitled"));
        builder.append(" score=").append(String.format(java.util.Locale.ROOT, "%.4f", chunk.score()));
        if (!blank(metadata.uri(), "").isBlank()) {
            builder.append(" uri=").append(metadata.uri());
        }
        if (!blank(metadata.page(), "").isBlank()) {
            builder.append(" page=").append(metadata.page());
        }
        if (!blank(metadata.section(), "").isBlank()) {
            builder.append(" section=").append(metadata.section());
        }
        builder.append("\nSource: ").append(blank(metadata.source(), "rag"));
        builder.append("\nContent: ").append(chunk.text());
        return builder.toString();
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
