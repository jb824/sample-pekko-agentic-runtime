package com.example.agent.rag.core;

import java.util.List;

public record EmbeddingVector(List<Float> values) {
    public EmbeddingVector {
        values = values == null ? List.of() : List.copyOf(values);
    }

    public int dimensions() {
        return values.size();
    }
}
