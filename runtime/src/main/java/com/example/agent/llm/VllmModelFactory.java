package com.example.agent.llm;

import com.example.agent.config.AppConfig;
import dev.langchain4j.model.chat.ChatModel;

public final class VllmModelFactory {
    private VllmModelFactory() {
    }

    public static ChatModel create(AppConfig config) {
        return new VllmChatModel(config);
    }
}
