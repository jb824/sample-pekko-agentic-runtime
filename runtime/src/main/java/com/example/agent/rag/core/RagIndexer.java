package com.example.agent.rag.core;

import java.util.concurrent.CompletionStage;

public interface RagIndexer {
    CompletionStage<RagIndexResult> index(IndexDocumentRequest request);

    CompletionStage<RagIndexResult> reindex(IndexDocumentRequest request);

    CompletionStage<Void> delete(String tenantId, String documentId);
}
