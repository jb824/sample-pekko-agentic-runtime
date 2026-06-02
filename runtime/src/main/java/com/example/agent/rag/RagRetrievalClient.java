package com.example.agent.rag;

import java.util.concurrent.CompletionStage;

public interface RagRetrievalClient {
    CompletionStage<RagRetrieveResponse> retrieve(RagRetrieveRequest request);
}
