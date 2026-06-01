package com.example.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        int maxToolRetries,
        Duration workflowTimeout,
        Duration toolTimeout,
        String testPrompt,
        List<String> testPrompts
) {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String DEFAULT_CONFIG_PATH = "config/agent.yaml";
    private static Path resolvedConfigPath = Path.of(DEFAULT_CONFIG_PATH);
    private static boolean configFileLoaded;

    public static AppConfig fromEnvironment() {
        String defaultPrompt = "Explain Apache Pekko typed actors in one practical paragraph.";
        Map<String, String> env = System.getenv();
        JsonNode yaml = loadYaml(env.get("AGENT_CONFIG_FILE"));

        return new AppConfig(
                value(env, "LLM_BACKEND", yaml, "llm.backend", "ollama"),
                value(env, "OLLAMA_BASE_URL", yaml, "llm.ollama.base_url", "http://localhost:11434"),
                value(env, "OLLAMA_MODEL", yaml, "llm.ollama.model", "granite4:3b"),
                value(env, "VLLM_BASE_URL", yaml, "llm.vllm.base_url", "http://localhost:8000/v1"),
                value(env, "VLLM_MODEL", yaml, "llm.vllm.model", "Qwen/Qwen2.5-3B-Instruct"),
                value(env, "VLLM_API_KEY", yaml, "llm.vllm.api_key", "EMPTY"),
                value(env, "VLLM_API_TYPE", yaml, "llm.vllm.api_type", "chat"),
                value(env, "VLLM_SYSTEM_PROMPT", yaml, "llm.vllm.system_prompt",
                        "You are a careful assistant. Follow the user request exactly. Return complete, concise answers. Do not invent product names."),
                intValue(env, "VLLM_MAX_TOKENS", yaml, "llm.vllm.max_tokens", 256),
                value(env, "VLLM_TOKENIZER_PATH", yaml, "llm.vllm.tokenizer_path", ""),
                value(env, "VLLM_GRPC_HOST", yaml, "llm.vllm.grpc.host", "localhost"),
                intValue(env, "VLLM_GRPC_PORT", yaml, "llm.vllm.grpc.port", 50051),
                boolValue(env, "VLLM_GRPC_PLAINTEXT", yaml, "llm.vllm.grpc.plaintext", true),
                doubleValue(env, "LLM_TEMPERATURE", yaml, "llm.temperature",
                        doubleValue(env, "OLLAMA_TEMPERATURE", yaml, "llm.ollama.temperature", 0.2)),
                Duration.ofSeconds(longValue(env, "LLM_TIMEOUT_SECONDS", yaml, "timeouts.llm_seconds", 90)),
                intValue(env, "LLM_THREADS", yaml, "runtime.llm_threads", 4),
                intValue(env, "LLM_QUEUE_SIZE", yaml, "runtime.llm_queue_size", 32),
                Math.max(1, intValue(env, "AGENT_REQUESTS", yaml, "runtime.requests", 1)),
                value(env, "AGENT_WORKFLOW", yaml, "workflow.mode", "planner-executor"),
                value(env, "AGENT_TOOLS", yaml, "workflow.tools", "time.now"),
                intValue(env, "AGENT_MAX_TOOLS", yaml, "workflow.max_tools", 3),
                intValue(env, "AGENT_MAX_STEPS", yaml, "workflow.max_steps", 4),
                intValue(env, "AGENT_TOOL_RETRY_ATTEMPTS", yaml, "workflow.tool_retry_attempts", 2),
                Duration.ofSeconds(longValue(env, "WORKFLOW_TIMEOUT_SECONDS", yaml, "timeouts.workflow_seconds", 180)),
                Duration.ofSeconds(longValue(env, "TOOL_TIMEOUT_SECONDS", yaml, "timeouts.tool_seconds", 30)),
                value(env, "AGENT_PROMPT", yaml, "prompts.default", defaultPrompt),
                prompts(env, yaml, defaultPrompt)
        );
    }

    public String promptForRequest(int requestNumber) {
        if (testPrompts.isEmpty()) {
            return testPrompt;
        }
        return testPrompts.get((requestNumber - 1) % testPrompts.size());
    }

    private static JsonNode loadYaml(String configuredPath) {
        boolean explicitPath = configuredPath != null && !configuredPath.isBlank();
        resolvedConfigPath = resolveConfigPath(configuredPath);
        Path yamlPath = resolvedConfigPath;
        if (!Files.exists(yamlPath)) {
            if (explicitPath) {
                throw new IllegalArgumentException("YAML config file not found: " + yamlPath.toAbsolutePath());
            }
            configFileLoaded = false;
            return null;
        }
        try {
            configFileLoaded = true;
            return YAML.readTree(Files.readString(yamlPath));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read YAML config file: " + yamlPath, exception);
        }
    }

    private static Path resolveConfigPath(String configuredPath) {
        String configured = configuredPath == null || configuredPath.isBlank() ? DEFAULT_CONFIG_PATH : configuredPath;
        Path direct = Path.of(configured);
        if (direct.isAbsolute()) {
            return direct;
        }
        Path cwd = Path.of("").toAbsolutePath();
        Path cwdCandidate = cwd.resolve(direct).normalize();
        if (Files.exists(cwdCandidate)) {
            return cwdCandidate;
        }
        Path parentCandidate = cwd.getParent() == null ? cwdCandidate : cwd.getParent().resolve(direct).normalize();
        return parentCandidate;
    }

    private static String value(Map<String, String> env, String envName, JsonNode yaml, String yamlPath, String defaultValue) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String yamlValue = yamlText(yaml, yamlPath);
        return yamlValue == null || yamlValue.isBlank() ? defaultValue : yamlValue;
    }

    private static int intValue(Map<String, String> env, String envName, JsonNode yaml, String yamlPath, int defaultValue) {
        return Integer.parseInt(value(env, envName, yaml, yamlPath, Integer.toString(defaultValue)));
    }

    private static long longValue(Map<String, String> env, String envName, JsonNode yaml, String yamlPath, long defaultValue) {
        return Long.parseLong(value(env, envName, yaml, yamlPath, Long.toString(defaultValue)));
    }

    private static double doubleValue(Map<String, String> env, String envName, JsonNode yaml, String yamlPath, double defaultValue) {
        return Double.parseDouble(value(env, envName, yaml, yamlPath, Double.toString(defaultValue)));
    }

    private static boolean boolValue(Map<String, String> env, String envName, JsonNode yaml, String yamlPath, boolean defaultValue) {
        return Boolean.parseBoolean(value(env, envName, yaml, yamlPath, Boolean.toString(defaultValue)));
    }

    private static String yamlText(JsonNode yaml, String path) {
        if (yaml == null) {
            return null;
        }
        JsonNode current = yaml;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            current = current.get(segment);
        }
        return current == null || current.isMissingNode() || current.isNull() ? null : current.asText();
    }

    private static List<String> prompts(Map<String, String> env, JsonNode yaml, String defaultPrompt) {
        String promptsFile = env.get("AGENT_PROMPTS_FILE");
        if (promptsFile == null || promptsFile.isBlank()) {
            promptsFile = yamlText(yaml, "prompts.file");
        }
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

        String prompts = env.get("AGENT_PROMPTS");
        if (prompts == null || prompts.isBlank()) {
            prompts = yamlText(yaml, "prompts.inline");
        }
        if (prompts != null && !prompts.isBlank()) {
            List<String> parsedPrompts = Arrays.stream(prompts.split("\\|"))
                    .map(String::trim)
                    .filter(prompt -> !prompt.isEmpty())
                    .toList();
            if (!parsedPrompts.isEmpty()) {
                return parsedPrompts;
            }
        }

        return List.of(value(env, "AGENT_PROMPT", yaml, "prompts.default", defaultPrompt));
    }

    public boolean hasPromptEnvOverrides() {
        return hasEnv("AGENT_PROMPT") || hasEnv("AGENT_PROMPTS") || hasEnv("AGENT_PROMPTS_FILE");
    }

    public String configPath() {
        return resolvedConfigPath.toString();
    }

    public boolean configFileLoaded() {
        return configFileLoaded;
    }

    private static boolean hasEnv(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }
}
