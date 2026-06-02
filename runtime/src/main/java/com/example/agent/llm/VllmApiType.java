package com.example.agent.llm;

import java.util.Locale;

enum VllmApiType {
    CHAT,
    COMPLETION;

    static VllmApiType fromConfig(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "chat", "chat-completions", "chat_completions" -> CHAT;
            case "completion", "completions" -> COMPLETION;
            default -> throw new IllegalArgumentException("Unsupported vLLM API type: " + value);
        };
    }
}
