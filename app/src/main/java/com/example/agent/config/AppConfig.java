package com.example.agent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public record AppConfig(
        String llmBackend,
        String ollamaBaseUrl,
        String ollamaModelName,
        String vllmBaseUrl,
        String vllmModelName,
        String vllmApiKey,
        String vllmApiType,
        String vllmSystemPrompt,
        int vllmMaxTokens,
        String vllmTokenizerPath,
        String vllmGrpcHost,
        int vllmGrpcPort,
        boolean vllmGrpcPlaintext,
        double temperature,
        Duration llmTimeout,
        int llmThreads,
        int llmQueueSize,
        int requestCount,
        String workflowMode,
        String enabledTools,
        int maxTools,
        int maxSteps,
        Duration workflowTimeout,
        Duration toolTimeout,
        String testPrompt,
        List<String> testPrompts
) {
    public static AppConfig fromEnvironment() {
        String defaultPrompt = "Explain Apache Pekko typed actors in one practical paragraph.";
        return new AppConfig(
                value("LLM_BACKEND", "ollama"),
                value("OLLAMA_BASE_URL", "http://localhost:11434"),
                value("OLLAMA_MODEL", "granite4:3b"),
                value("VLLM_BASE_URL", "http://localhost:8000/v1"),
                value("VLLM_MODEL", "Qwen/Qwen2.5-3B-Instruct"),
                value("VLLM_API_KEY", "EMPTY"),
                value("VLLM_API_TYPE", "chat"),
                value("VLLM_SYSTEM_PROMPT", "You are a careful assistant. Follow the user request exactly. Return complete, concise answers. Do not invent product names."),
                intValue("VLLM_MAX_TOKENS", 256),
                value("VLLM_TOKENIZER_PATH", ""),
                value("VLLM_GRPC_HOST", "localhost"),
                intValue("VLLM_GRPC_PORT", 50051),
                booleanValue("VLLM_GRPC_PLAINTEXT", true),
                doubleValue("LLM_TEMPERATURE", doubleValue("OLLAMA_TEMPERATURE", 0.2)),
                Duration.ofSeconds(longValue("LLM_TIMEOUT_SECONDS", 90)),
                intValue("LLM_THREADS", 4),
                intValue("LLM_QUEUE_SIZE", 32),
                Math.max(1, intValue("AGENT_REQUESTS", 1)),
                value("AGENT_WORKFLOW", "planner-executor"),
                value("AGENT_TOOLS", "time.now"),
                intValue("AGENT_MAX_TOOLS", 3),
                intValue("AGENT_MAX_STEPS", 4),
                Duration.ofSeconds(longValue("WORKFLOW_TIMEOUT_SECONDS", 180)),
                Duration.ofSeconds(longValue("TOOL_TIMEOUT_SECONDS", 30)),
                value("AGENT_PROMPT", defaultPrompt),
                prompts(defaultPrompt)
        );
    }

    public String promptForRequest(int requestNumber) {
        if (testPrompts.isEmpty()) {
            return testPrompt;
        }
        return testPrompts.get((requestNumber - 1) % testPrompts.size());
    }

    private static String value(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intValue(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static long longValue(String name, long defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }

    private static double doubleValue(String name, double defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value);
    }

    private static boolean booleanValue(String name, boolean defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    private static List<String> prompts(String defaultPrompt) {
        String promptsFile = System.getenv("AGENT_PROMPTS_FILE");
        if (promptsFile != null && !promptsFile.isBlank()) {
            try {
                List<String> prompts = Files.readAllLines(Path.of(promptsFile)).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#"))
                        .toList();
                if (!prompts.isEmpty()) {
                    return prompts;
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to read AGENT_PROMPTS_FILE: " + promptsFile, exception);
            }
        }

        String prompts = System.getenv("AGENT_PROMPTS");
        if (prompts != null && !prompts.isBlank()) {
            List<String> parsedPrompts = Arrays.stream(prompts.split("\\|"))
                    .map(String::trim)
                    .filter(prompt -> !prompt.isEmpty())
                    .toList();
            if (!parsedPrompts.isEmpty()) {
                return parsedPrompts;
            }
        }

        return List.of(value("AGENT_PROMPT", defaultPrompt));
    }
}
