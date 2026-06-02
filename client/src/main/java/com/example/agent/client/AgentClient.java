package com.example.agent.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration defaultTimeout;

    private AgentClient(HttpClient httpClient, URI baseUri, Duration defaultTimeout) {
        this.httpClient = httpClient;
        this.baseUri = baseUri;
        this.defaultTimeout = defaultTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<AgentRunResult> run(AgentSystem agentSystem, String input) {
        return run(UUID.randomUUID().toString(), agentSystem, input, defaultTimeout);
    }

    public CompletionStage<AgentRunResult> run(String requestId, AgentSystem agentSystem, String input, Duration timeout) {
        return invoke(new AgentRunPayload(requestId, input, timeout.toMillis(), agentSystem), timeout);
    }

    CompletionStage<AgentTaskState> startTask(String taskId, AgentSystem agentSystem, String input, Duration timeout) {
        return invokeTask(new AgentRunPayload(taskId, input, timeout.toMillis(), agentSystem), timeout);
    }

    CompletionStage<AgentTaskState> getTask(String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/agents/tasks/" + taskId))
                    .timeout(defaultTimeout.plusSeconds(5))
                    .GET()
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            try {
                                return CompletableFuture.completedFuture(JSON.readValue(response.body(), AgentTaskState.class));
                            } catch (IOException exception) {
                                return CompletableFuture.failedFuture(exception);
                            }
                        }
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Task lookup failed with HTTP " + response.statusCode() + ": " + response.body()
                        ));
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<AgentRunResult> invoke(AgentRunPayload payload, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/agents/execute"))
                    .timeout(timeout.plusSeconds(5))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            try {
                                return CompletableFuture.completedFuture(JSON.readValue(response.body(), AgentRunResult.class));
                            } catch (IOException exception) {
                                return CompletableFuture.failedFuture(exception);
                            }
                        }
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Agent run failed with HTTP " + response.statusCode() + ": " + response.body()
                        ));
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<AgentTaskState> invokeTask(AgentRunPayload payload, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/agents/tasks"))
                    .timeout(timeout.plusSeconds(5))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            try {
                                return CompletableFuture.completedFuture(JSON.readValue(response.body(), AgentTaskState.class));
                            } catch (IOException exception) {
                                return CompletableFuture.failedFuture(exception);
                            }
                        }
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Agent task failed with HTTP " + response.statusCode() + ": " + response.body()
                        ));
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public static final class Builder {
        private HttpClient httpClient = HttpClient.newHttpClient();
        private URI baseUri = URI.create("http://localhost:8080");
        private Duration defaultTimeout = Duration.ofSeconds(60);

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient);
            return this;
        }

        public Builder baseUri(String baseUri) {
            this.baseUri = URI.create(Objects.requireNonNull(baseUri));
            return this;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri);
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
            return this;
        }

        public AgentClient build() {
            return new AgentClient(httpClient, baseUri, defaultTimeout);
        }
    }

    private record AgentRunPayload(String requestId, String input, long timeoutMs, AgentSystem system) {
    }
}
