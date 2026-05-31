package com.example.agent.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

final class AppConfigLoader {
    private static final Config CONFIG = ConfigFactory.load();

    private AppConfigLoader() {
    }

    static AppConfig load() {
        String defaultPrompt = "Explain Apache Pekko typed actors in one practical paragraph.";
        return new AppConfig(
                value("APP_MODE", "agent"),
                value("LLM_BACKEND", "ollama"),
                value("OLLAMA_BASE_URL", "http://localhost:11434"),
                value("OLLAMA_MODEL", "granite4:3b"),
                value("VLLM_BASE_URL", "http://localhost:8000/v1"),
                value("VLLM_MODEL", "ibm-granite/granite-4.1-3b"),
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
                value("AGENT_WORKFLOW", "research"),
                value("AGENT_TOOLS", "time.now"),
                intValue("AGENT_MAX_TOOLS", 1),
                intValue("AGENT_MAX_STEPS", 3),
                value("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                value("KAFKA_TOPIC", "agent.ingestion.commands.v1"),
                value("KAFKA_INPUT_TOPIC", value("KAFKA_TOPIC", "agent.commands.v1")),
                value("KAFKA_OUTPUT_TOPIC", "agent.workflow.events.v1"),
                value("KAFKA_GROUP_ID", "pekko-agent-runtime"),
                value("GUARDIAN_QUERY", ""),
                value("GUARDIAN_SECTION", ""),
                Math.max(1, intValue("GUARDIAN_MAX_PAGES", 1)),
                Duration.ofSeconds(longValue("WORKFLOW_TIMEOUT_SECONDS", 180)),
                Duration.ofSeconds(longValue("TOOL_TIMEOUT_SECONDS", 60)),
                value("AGENT_PROMPT", defaultPrompt),
                prompts(defaultPrompt)
        );
    }

    private static String value(String name, String defaultValue) {
        String resolved = rawValue(name);
        return resolved == null || resolved.isBlank() ? defaultValue : resolved;
    }

    private static int intValue(String name, int defaultValue) {
        String value = rawValue(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static long longValue(String name, long defaultValue) {
        String value = rawValue(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }

    private static double doubleValue(String name, double defaultValue) {
        String value = rawValue(name);
        return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value);
    }

    private static boolean booleanValue(String name, boolean defaultValue) {
        String value = rawValue(name);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    private static String rawValue(String envName) {
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        for (String key : candidateConfigKeys(envName)) {
            if (CONFIG.hasPath(key)) {
                Object configValue = CONFIG.getAnyRef(key);
                if (configValue != null) {
                    return String.valueOf(configValue);
                }
            }
        }
        return null;
    }

    private static List<String> candidateConfigKeys(String envName) {
        String lower = envName.toLowerCase();
        return List.of(
                envName,
                lower,
                lower.replace('_', '.'),
                lower.replace('_', '-')
        );
    }

    private static List<String> prompts(String defaultPrompt) {
        String promptsFile = rawValue("AGENT_PROMPTS_FILE");
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

        String prompts = rawValue("AGENT_PROMPTS");
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
