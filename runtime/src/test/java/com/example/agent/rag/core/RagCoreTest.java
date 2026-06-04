package com.example.agent.rag.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RagCoreTest {
    @Test
    void vectorSearchAppliesTenantFilterAndMinScore() {
        SimpleHashEmbeddingClient embeddings = new SimpleHashEmbeddingClient(32);
        InMemoryVectorStore store = new InMemoryVectorStore();

        RagDocumentChunk tenantA = chunk("tenant-a", "doc-a", "alpha beta");
        RagDocumentChunk tenantB = chunk("tenant-b", "doc-b", "alpha beta");
        store.upsert(
                List.of(tenantA, tenantB),
                embeddings.embedBatch(List.of(tenantA.text(), tenantB.text())).toCompletableFuture().join()
        ).toCompletableFuture().join();

        EmbeddingVector query = embeddings.embed("alpha").toCompletableFuture().join();
        List<RagSearchResult> results = store.similaritySearch(
                query,
                new RagSearchRequest(new RagSecurityContext("tenant-a", "", Map.of()), 5, 0.1, Map.of())
        ).toCompletableFuture().join();

        assertEquals(1, results.size());
        assertEquals("tenant-a", results.getFirst().chunk().tenantId());

        List<RagSearchResult> highThreshold = store.similaritySearch(
                query,
                new RagSearchRequest(new RagSecurityContext("tenant-a", "", Map.of()), 5, 1.1, Map.of())
        ).toCompletableFuture().join();
        assertTrue(highThreshold.isEmpty());
    }

    @Test
    void indexingSkipsUnchangedDocumentHash() {
        SimpleHashEmbeddingClient embeddings = new SimpleHashEmbeddingClient(32);
        DefaultRagIndexer indexer = new DefaultRagIndexer(embeddings, new InMemoryVectorStore(), new DocumentChunker(256, 0));
        IndexDocumentRequest request = new IndexDocumentRequest(
                "doc-1",
                "tenant-a",
                new RagSourceMetadata("manual", "Doc", "file://doc", "", "", Map.of()),
                "alpha beta gamma"
        );

        RagIndexResult first = indexer.index(request).toCompletableFuture().join();
        RagIndexResult second = indexer.index(request).toCompletableFuture().join();

        assertTrue(first.chunksIndexed() > 0);
        assertTrue(second.skipped());
        assertEquals(0, second.chunksIndexed());
    }

    private static RagDocumentChunk chunk(String tenantId, String documentId, String text) {
        return new RagDocumentChunk(
                tenantId,
                documentId,
                documentId + ":0",
                text,
                new RagChunkMetadata("test", "Title", "uri://" + documentId, "", "", "hash", java.time.Instant.EPOCH, java.time.Instant.EPOCH, Map.of())
        );
    }
}
