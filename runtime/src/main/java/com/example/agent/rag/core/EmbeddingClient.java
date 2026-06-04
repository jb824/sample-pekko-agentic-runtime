package com.example.agent.rag.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface EmbeddingClient {
    CompletionStage<EmbeddingVector> embed(String text);

    CompletionStage<List<EmbeddingVector>> embedBatch(List<String> texts);

    String modelName();

    int dimensions();
}
