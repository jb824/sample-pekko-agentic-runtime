package com.example.agent.rag.core;

import java.util.List;

public record RagRetrievalResult(List<RetrievedChunk> chunks) {
    public RagRetrievalResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static RagRetrievalResult empty() {
        return new RagRetrievalResult(List.of());
    }
}
