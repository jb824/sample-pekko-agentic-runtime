package com.example.agent.llm;

import com.example.agent.config.AppConfig;
import dev.langchain4j.model.chat.ChatModel;

public final class VllmGrpcModelFactory {
    private VllmGrpcModelFactory() {
    }

    public static ChatModel create(AppConfig config) {
        return new VllmGrpcChatModel(config);
    }
}
