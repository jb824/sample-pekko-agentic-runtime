package com.example.agent.rag.core;

import com.example.agent.runtime.telemetry.Telemetry;
import io.opentelemetry.api.trace.Span;

import java.util.List;
import java.util.concurrent.CompletionStage;

public final class DefaultRagRetriever implements RagRetriever {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final double minScore;

    public DefaultRagRetriever(EmbeddingClient embeddingClient, VectorStore vectorStore, double minScore) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.minScore = minScore;
    }

    @Override
    public CompletionStage<RagRetrievalResult> retrieve(String query, RagSecurityContext securityContext, int topK) {
        Span span = Telemetry.startInternalSpan("rag.retrieve");
        long started = System.nanoTime();
        span.setAttribute("rag.enabled", true);
        span.setAttribute("rag.tenant_id", securityContext.tenantId());
        span.setAttribute("rag.top_k", topK);
        return embeddingClient.embed(query)
                .thenCompose(vector -> {
                    span.setAttribute("rag.embedding.model", embeddingClient.modelName());
                    span.setAttribute("rag.embedding.dimensions", vector.dimensions());
                    return vectorStore.similaritySearch(vector, new RagSearchRequest(securityContext, topK, minScore, securityContext.filters()));
                })
                .thenApply(results -> {
                    List<RetrievedChunk> chunks = results.stream()
                            .map(result -> new RetrievedChunk(
                                    result.chunk().tenantId(),
                                    result.chunk().documentId(),
                                    result.chunk().chunkId(),
                                    result.chunk().text(),
                                    result.score(),
                                    result.chunk().metadata()
                            ))
                            .toList();
                    span.setAttribute("rag.chunks_retrieved", chunks.size());
                    if (!chunks.isEmpty()) {
                        span.setAttribute("rag.score.max", chunks.getFirst().score());
                        span.setAttribute("rag.score.min", chunks.getLast().score());
                    }
                    span.setAttribute("rag.latency_ms", (System.nanoTime() - started) / 1_000_000L);
                    span.end();
                    return new RagRetrievalResult(chunks);
                })
                .exceptionallyCompose(failure -> {
                    span.recordException(failure);
                    span.end();
                    return java.util.concurrent.CompletableFuture.failedFuture(failure);
                });
    }
}
