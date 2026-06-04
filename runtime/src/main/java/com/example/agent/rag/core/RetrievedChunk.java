package com.example.agent.rag.core;

public record RetrievedChunk(
        String tenantId,
        String documentId,
        String chunkId,
        String text,
        double score,
        RagChunkMetadata metadata
) {
    public String citation() {
        if (metadata == null) {
            return documentId;
        }
        if (metadata.uri() != null && !metadata.uri().isBlank()) {
            return metadata.uri();
        }
        if (metadata.title() != null && !metadata.title().isBlank()) {
            return metadata.title();
        }
        return documentId;
    }
}
