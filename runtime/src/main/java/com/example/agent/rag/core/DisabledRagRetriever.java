package com.example.agent.rag.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DisabledRagRetriever implements RagRetriever {
    @Override
    public CompletionStage<RagRetrievalResult> retrieve(String query, RagSecurityContext securityContext, int topK) {
        return CompletableFuture.completedFuture(RagRetrievalResult.empty());
    }
}
