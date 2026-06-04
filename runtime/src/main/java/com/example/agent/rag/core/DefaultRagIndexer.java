package com.example.agent.rag.core;

import com.example.agent.runtime.telemetry.Telemetry;
import io.opentelemetry.api.trace.Span;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultRagIndexer implements RagIndexer {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final DocumentChunker chunker;
    private final Clock clock;
    private final Map<String, String> documentHashes = new ConcurrentHashMap<>();

    public DefaultRagIndexer(EmbeddingClient embeddingClient, VectorStore vectorStore, DocumentChunker chunker) {
        this(embeddingClient, vectorStore, chunker, Clock.systemUTC());
    }

    public DefaultRagIndexer(EmbeddingClient embeddingClient, VectorStore vectorStore, DocumentChunker chunker, Clock clock) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.clock = clock;
    }

    @Override
    public CompletionStage<RagIndexResult> index(IndexDocumentRequest request) {
        String hash = sha256(request.text());
        String key = key(request.tenantId(), request.documentId());
        if (hash.equals(documentHashes.get(key))) {
            return java.util.concurrent.CompletableFuture.completedFuture(new RagIndexResult(request.tenantId(), request.documentId(), 0, true));
        }
        return reindex(request);
    }

    @Override
    public CompletionStage<RagIndexResult> reindex(IndexDocumentRequest request) {
        Span span = Telemetry.startInternalSpan("rag.index");
        long started = System.nanoTime();
        String hash = sha256(request.text());
        List<String> texts = chunker.split(request.text());
        Instant now = clock.instant();
        List<RagDocumentChunk> chunks = java.util.stream.IntStream.range(0, texts.size())
                .mapToObj(index -> new RagDocumentChunk(
                        request.tenantId(),
                        request.documentId(),
                        request.documentId() + ":" + index,
                        texts.get(index),
                        new RagChunkMetadata(
                                request.source().source(),
                                request.source().title(),
                                request.source().uri(),
                                request.source().page(),
                                request.source().section(),
                                hash,
                                now,
                                now,
                                request.source().attributes()
                        )
                ))
                .toList();
        span.setAttribute("rag.tenant_id", request.tenantId());
        span.setAttribute("rag.document_id", request.documentId());
        span.setAttribute("rag.ingest.chunk_count", chunks.size());
        return vectorStore.deleteDocument(request.tenantId(), request.documentId())
                .thenCompose(ignored -> embeddingClient.embedBatch(texts))
                .thenCompose(vectors -> vectorStore.upsert(chunks, vectors))
                .thenApply(ignored -> {
                    documentHashes.put(key(request.tenantId(), request.documentId()), hash);
                    span.setAttribute("rag.latency_ms", (System.nanoTime() - started) / 1_000_000L);
                    span.end();
                    return new RagIndexResult(request.tenantId(), request.documentId(), chunks.size(), false);
                })
                .exceptionallyCompose(failure -> {
                    span.recordException(failure);
                    span.end();
                    return java.util.concurrent.CompletableFuture.failedFuture(failure);
                });
    }

    @Override
    public CompletionStage<Void> delete(String tenantId, String documentId) {
        documentHashes.remove(key(tenantId, documentId));
        return vectorStore.deleteDocument(tenantId, documentId);
    }

    private static String key(String tenantId, String documentId) {
        return (tenantId == null ? "default" : tenantId) + "\u0000" + documentId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
