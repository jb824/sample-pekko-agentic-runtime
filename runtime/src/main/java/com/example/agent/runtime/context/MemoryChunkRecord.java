package com.example.agent.runtime.context;

import java.time.Instant;
import java.util.UUID;

public record MemoryChunkRecord(
        String tenantId,
        String conversationId,
        int bucket,
        int chunkIndex,
        UUID chunkId,
        int tokenCount,
        String content,
        String contentHash,
        Instant createdAt
) {
    public MemoryChunkRecord {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        conversationId = conversationId == null ? "" : conversationId;
        bucket = Math.max(0, bucket);
        chunkIndex = Math.max(0, chunkIndex);
        chunkId = chunkId == null ? UUID.randomUUID() : chunkId;
        tokenCount = Math.max(0, tokenCount);
        content = content == null ? "" : content;
        contentHash = contentHash == null ? "" : contentHash;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }
}
