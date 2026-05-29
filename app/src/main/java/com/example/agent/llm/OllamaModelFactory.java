package com.example.agent.llm;

import com.example.agent.config.AppConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

public final class OllamaModelFactory {
    private OllamaModelFactory() {
    }

    public static ChatModel create(AppConfig config) {
        return OllamaChatModel.builder()
                .baseUrl(config.ollamaBaseUrl())
                .modelName(config.ollamaModelName())
                .temperature(config.temperature())
                .timeout(config.llmTimeout())
                .build();
    }
}
