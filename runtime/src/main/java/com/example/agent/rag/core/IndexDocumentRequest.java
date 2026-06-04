package com.example.agent.rag.core;

public record IndexDocumentRequest(
        String documentId,
        String tenantId,
        RagSourceMetadata source,
        String text
) {
    public IndexDocumentRequest {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        source = source == null ? new RagSourceMetadata("", "", "", "", "", java.util.Map.of()) : source;
        text = text == null ? "" : text;
    }
}
