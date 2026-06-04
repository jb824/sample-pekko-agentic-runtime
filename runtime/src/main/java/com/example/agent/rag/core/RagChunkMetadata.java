package com.example.agent.rag.core;

import java.time.Instant;
import java.util.Map;

public record RagChunkMetadata(
        String source,
        String title,
        String uri,
        String page,
        String section,
        String contentHash,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> attributes
) {
    public RagChunkMetadata {
        source = source == null ? "" : source;
        title = title == null ? "" : title;
        uri = uri == null ? "" : uri;
        page = page == null ? "" : page;
        section = section == null ? "" : section;
        contentHash = contentHash == null ? "" : contentHash;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
