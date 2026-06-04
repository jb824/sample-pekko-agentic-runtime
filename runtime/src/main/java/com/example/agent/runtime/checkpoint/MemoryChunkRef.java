package com.example.agent.runtime.checkpoint;

import java.util.UUID;

public record MemoryChunkRef(
        String tenantId,
        String conversationId,
        UUID chunkId,
        int tokenCount,
        String contentHash
) {
    public MemoryChunkRef {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        conversationId = conversationId == null ? "" : conversationId;
        contentHash = contentHash == null ? "" : contentHash;
    }
}
