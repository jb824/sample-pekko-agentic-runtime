package com.example.agent.rag.core;

import java.util.Objects;

public record RagDocumentChunk(
        String tenantId,
        String documentId,
        String chunkId,
        String text,
        RagChunkMetadata metadata
) {
    public RagDocumentChunk {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        documentId = Objects.requireNonNull(documentId);
        chunkId = Objects.requireNonNull(chunkId);
        text = text == null ? "" : text;
        metadata = Objects.requireNonNull(metadata);
    }
}
