package com.example.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class HttpRagRetrievalClient implements RagRetrievalClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final String apiKey;

    public HttpRagRetrievalClient(String baseUrl, Duration timeout, String apiKey) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = timeout;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public CompletionStage<RagRetrieveResponse> retrieve(RagRetrieveRequest request) {
        try {
            String payload = JSON.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/retrieve"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("RAG retrieval service returned HTTP " + response.statusCode())
                            );
                        }
                        try {
                            return CompletableFuture.completedFuture(JSON.readValue(response.body(), RagRetrieveResponse.class));
                        } catch (Exception exception) {
                            return CompletableFuture.failedFuture(exception);
                        }
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
