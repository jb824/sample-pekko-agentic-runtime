package com.example.agent.rag.core;

import java.util.ArrayList;
import java.util.List;

public final class DocumentChunker {
    private final int maxChunkChars;
    private final int overlapChars;

    public DocumentChunker(int maxChunkChars, int overlapChars) {
        this.maxChunkChars = Math.max(256, maxChunkChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, this.maxChunkChars / 2));
    }

    public List<String> split(String text) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + maxChunkChars);
            if (end < normalized.length()) {
                int boundary = normalized.lastIndexOf('\n', end);
                if (boundary <= start) {
                    boundary = normalized.lastIndexOf(' ', end);
                }
                if (boundary > start) {
                    end = boundary;
                }
            }
            chunks.add(normalized.substring(start, end).strip());
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlapChars, start + 1);
        }
        return chunks.stream().filter(chunk -> !chunk.isBlank()).toList();
    }
}
