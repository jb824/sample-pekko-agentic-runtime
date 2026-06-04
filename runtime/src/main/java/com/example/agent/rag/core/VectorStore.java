package com.example.agent.rag.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface VectorStore {
    CompletionStage<Void> upsert(List<RagDocumentChunk> chunks, List<EmbeddingVector> vectors);

    CompletionStage<Void> deleteDocument(String tenantId, String documentId);

    CompletionStage<Void> deleteTenant(String tenantId);

    CompletionStage<List<RagSearchResult>> similaritySearch(EmbeddingVector vector, RagSearchRequest request);
}
