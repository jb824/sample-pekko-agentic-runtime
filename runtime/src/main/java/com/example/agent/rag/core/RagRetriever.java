package com.example.agent.rag.core;

import java.util.concurrent.CompletionStage;

public interface RagRetriever {
    CompletionStage<RagRetrievalResult> retrieve(String query, RagSecurityContext securityContext, int topK);
}
