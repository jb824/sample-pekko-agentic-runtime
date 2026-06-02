package com.example.agent.rag.service;

import java.util.List;

public interface TextEmbeddingClient {
    List<Float> embed(String text);

    default List<Float> embedQuery(String text) {
        return embed(text);
    }

    default List<Float> embedPassage(String text) {
        return embed(text);
    }
}
