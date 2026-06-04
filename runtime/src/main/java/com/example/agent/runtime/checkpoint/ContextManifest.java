package com.example.agent.runtime.checkpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContextManifest(
        String tenantId,
        String agentId,
        String workflowId,
        long version,
        UUID summaryId,
        List<UUID> recentChunkIds,
        List<UUID> retrievedChunkIds,
        ContextArtifactRef artifactRef,
        int tokenBudget,
        Instant createdAt
) {
    public ContextManifest {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        agentId = agentId == null ? "" : agentId;
        workflowId = workflowId == null ? "" : workflowId;
        version = Math.max(0L, version);
        recentChunkIds = recentChunkIds == null ? List.of() : List.copyOf(recentChunkIds);
        retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
        artifactRef = artifactRef == null ? new ContextArtifactRef("", "", "", 0L) : artifactRef;
        tokenBudget = Math.max(0, tokenBudget);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }
}
