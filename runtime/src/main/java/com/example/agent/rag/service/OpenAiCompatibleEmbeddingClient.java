package com.example.agent.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleEmbeddingClient implements TextEmbeddingClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiCompatibleEmbeddingClient(String endpoint, String apiKey, String model, Duration timeout) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = stripTrailingSlash(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public List<Float> embed(String text) {
        try {
            String payload = JSON.writeValueAsString(Map.of(
                    "model", model,
                    "input", text == null ? "" : text
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/embeddings"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Embedding endpoint returned HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode vectorNode = root.path("data").path(0).path("embedding");
            if (!vectorNode.isArray() || vectorNode.isEmpty()) {
                throw new IllegalStateException("Embedding response did not include a vector");
            }
            List<Float> vector = new ArrayList<>();
            vectorNode.forEach(value -> vector.add((float) value.asDouble()));
            return normalize(vector);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to generate embedding", exception);
        }
    }

    private static List<Float> normalize(List<Float> vector) {
        double norm = Math.sqrt(vector.stream().mapToDouble(value -> value * value).sum());
        if (norm == 0.0) {
            return vector;
        }
        List<Float> normalized = new ArrayList<>(vector.size());
        for (Float value : vector) {
            normalized.add((float) (value / norm));
        }
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
