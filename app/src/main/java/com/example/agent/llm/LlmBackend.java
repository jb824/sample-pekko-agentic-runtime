package com.example.agent.llm;

import java.util.Locale;

public enum LlmBackend {
    OLLAMA,
    VLLM,
    VLLM_GRPC;

    public static LlmBackend fromConfig(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "ollama" -> OLLAMA;
            case "vllm" -> VLLM;
            case "vllm-grpc", "vllm_grpc", "grpc" -> VLLM_GRPC;
            default -> throw new IllegalArgumentException("Unsupported LLM backend: " + value);
        };
    }
}
