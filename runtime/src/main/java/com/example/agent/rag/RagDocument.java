package com.example.agent.rag;

public record RagDocument(
        String id,
        String title,
        String reference,
        String snippet,
        double score,
        String source
) {
}
