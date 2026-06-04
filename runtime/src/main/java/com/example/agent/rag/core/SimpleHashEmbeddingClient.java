package com.example.agent.rag.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class SimpleHashEmbeddingClient implements EmbeddingClient {
    private final int dimensions;

    public SimpleHashEmbeddingClient(int dimensions) {
        this.dimensions = Math.max(8, dimensions);
    }

    @Override
    public CompletionStage<EmbeddingVector> embed(String text) {
        float[] vector = new float[dimensions];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.isBlank()) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), dimensions);
            vector[index] += 1.0f;
        }
        return CompletableFuture.completedFuture(new EmbeddingVector(normalize(vector)));
    }

    @Override
    public CompletionStage<List<EmbeddingVector>> embedBatch(List<String> texts) {
        List<EmbeddingVector> embeddings = new ArrayList<>();
        for (String text : texts == null ? List.<String>of() : texts) {
            embeddings.add(embed(text).toCompletableFuture().join());
        }
        return CompletableFuture.completedFuture(embeddings);
    }

    @Override
    public String modelName() {
        return "simple-hash";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static List<Float> normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(norm == 0.0 ? 0.0f : (float) (value / norm));
        }
        return values;
    }
}
