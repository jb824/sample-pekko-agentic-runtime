package com.example.agent.llm;

import com.example.agent.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class VllmChatModel implements ChatModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(VllmChatModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final VllmApiType apiType;
    private final String systemPrompt;
    private final int maxTokens;
    private final double temperature;
    private final Duration timeout;

    VllmChatModel(AppConfig config) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.llmTimeout())
                .build();
        this.baseUrl = stripTrailingSlash(config.vllmBaseUrl());
        this.modelName = config.vllmModelName();
        this.apiKey = config.vllmApiKey();
        this.apiType = VllmApiType.fromConfig(config.vllmApiType());
        this.systemPrompt = config.vllmSystemPrompt();
        this.maxTokens = config.vllmMaxTokens();
        this.temperature = config.temperature();
        this.timeout = config.llmTimeout();
    }

    @Override
    public String chat(String userMessage) {
        try {
            return switch (apiType) {
                case CHAT -> chatCompletion(userMessage);
                case COMPLETION -> completion(userMessage);
            };
        } catch (IOException exception) {
            throw new IllegalStateException("vLLM request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("vLLM request interrupted", exception);
        }
    }

    private String chatCompletion(String userMessage) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", systemPrompt
                        ),
                        Map.of(
                                "role", "user",
                                "content", userMessage
                        )
                ),
                "temperature", temperature,
                "max_tokens", maxTokens
        );
        JsonNode response = post("/chat/completions", body);
        LOGGER.info("vLLM response:\n{}", response.at("/choices/0/text").asText());
        return response.at("/choices/0/message/content").asText();
    }

    private String completion(String prompt) throws IOException, InterruptedException {
        String wrappedPrompt = """
                System:
                %s

                User:
                %s

                Assistant:
                """.formatted(systemPrompt, prompt);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "prompt", wrappedPrompt,
                "temperature", temperature,
                "max_tokens", maxTokens
        );
        JsonNode response = post("/completions", body);
        LOGGER.info("vLLM response:\n{}", response.at("/choices/0/text").asText());
        return response.at("/choices/0/text").asText();
    }

    private JsonNode post(String path, Map<String, Object> body) throws IOException, InterruptedException {
        String requestBody = JSON.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("vLLM returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
