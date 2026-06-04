package com.example.agent.rag.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryVectorStore implements VectorStore {
    private final Map<String, StoredChunk> chunks = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Void> upsert(List<RagDocumentChunk> chunks, List<EmbeddingVector> vectors) {
        if (chunks == null || vectors == null || chunks.size() != vectors.size()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("chunks and vectors must be non-null and equal sized"));
        }
        for (int index = 0; index < chunks.size(); index++) {
            RagDocumentChunk chunk = chunks.get(index);
            this.chunks.put(key(chunk.tenantId(), chunk.documentId(), chunk.chunkId()), new StoredChunk(chunk, vectors.get(index)));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> deleteDocument(String tenantId, String documentId) {
        chunks.keySet().removeIf(key -> key.startsWith(prefix(tenantId, documentId)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> deleteTenant(String tenantId) {
        chunks.keySet().removeIf(key -> key.startsWith((tenantId == null ? "default" : tenantId) + "\u0000"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<RagSearchResult>> similaritySearch(EmbeddingVector vector, RagSearchRequest request) {
        String tenantId = request.securityContext().tenantId();
        List<RagSearchResult> results = chunks.values().stream()
                .filter(stored -> stored.chunk().tenantId().equals(tenantId))
                .filter(stored -> matchesFilters(stored.chunk(), request.filters()))
                .map(stored -> new RagSearchResult(stored.chunk(), cosine(vector, stored.vector())))
                .filter(result -> result.score() >= request.minScore())
                .sorted(Comparator.comparingDouble(RagSearchResult::score).reversed())
                .limit(request.topK())
                .toList();
        return CompletableFuture.completedFuture(results);
    }

    private static boolean matchesFilters(RagDocumentChunk chunk, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String actual = chunk.metadata().attributes().get(filter.getKey());
            if (!Objects.equals(actual, filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static double cosine(EmbeddingVector left, EmbeddingVector right) {
        int dimensions = Math.min(left.values().size(), right.values().size());
        if (dimensions == 0) {
            return 0.0;
        }
        double dot = 0.0;
        for (int index = 0; index < dimensions; index++) {
            dot += left.values().get(index) * right.values().get(index);
        }
        return dot;
    }

    private static String key(String tenantId, String documentId, String chunkId) {
        return prefix(tenantId, documentId) + chunkId;
    }

    private static String prefix(String tenantId, String documentId) {
        return (tenantId == null ? "default" : tenantId) + "\u0000" + documentId + "\u0000";
    }

    private record StoredChunk(RagDocumentChunk chunk, EmbeddingVector vector) {
    }
}
