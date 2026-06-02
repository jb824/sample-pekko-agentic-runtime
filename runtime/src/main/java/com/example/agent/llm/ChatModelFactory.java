package com.example.agent.llm;

import com.example.agent.config.AppConfig;
import dev.langchain4j.model.chat.ChatModel;

public final class ChatModelFactory {
    private ChatModelFactory() {
    }

    public static ChatModel create(AppConfig config) {
        return switch (LlmBackend.fromConfig(config.llmBackend())) {
            case OLLAMA -> OllamaModelFactory.create(config);
            case VLLM -> VllmModelFactory.create(config);
            case VLLM_GRPC -> VllmGrpcModelFactory.create(config);
        };
    }
}
