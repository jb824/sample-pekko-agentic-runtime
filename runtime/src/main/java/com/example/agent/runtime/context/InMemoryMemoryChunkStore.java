package com.example.agent.runtime.context;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMemoryChunkStore implements MemoryChunkStore {
    private final Map<UUID, MemoryChunkRecord> chunks = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Void> save(MemoryChunkRecord chunk) {
        chunks.put(chunk.chunkId(), chunk);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<MemoryChunkRecord>> recent(String tenantId, String conversationId, int bucket, int limit) {
        String normalizedTenant = normalizeTenant(tenantId);
        return CompletableFuture.completedFuture(chunks.values().stream()
                .filter(chunk -> chunk.tenantId().equals(normalizedTenant))
                .filter(chunk -> chunk.conversationId().equals(conversationId))
                .filter(chunk -> chunk.bucket() == bucket)
                .sorted(Comparator.comparingInt(MemoryChunkRecord::chunkIndex).reversed())
                .limit(Math.max(0, limit))
                .sorted(Comparator.comparingInt(MemoryChunkRecord::chunkIndex))
                .toList());
    }

    @Override
    public CompletionStage<List<MemoryChunkRecord>> findByIds(String tenantId, String conversationId, List<UUID> chunkIds) {
        String normalizedTenant = normalizeTenant(tenantId);
        return CompletableFuture.completedFuture((chunkIds == null ? List.<UUID>of() : chunkIds).stream()
                .map(chunks::get)
                .filter(chunk -> chunk != null && chunk.tenantId().equals(normalizedTenant) && chunk.conversationId().equals(conversationId))
                .toList());
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }
}
