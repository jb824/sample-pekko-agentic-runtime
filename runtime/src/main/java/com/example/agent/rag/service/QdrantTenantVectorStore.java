package com.example.agent.rag.service;

import com.example.agent.rag.RagDocument;
import com.example.agent.rag.RagRetrieveRequest;
import com.example.agent.rag.RagRetrieveResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class QdrantTenantVectorStore implements TenantVectorStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final String qdrantUrl;
    private final String apiKey;
    private final TextEmbeddingClient embeddingClient;
    private final Duration timeout;
    private final ConcurrentMap<String, Boolean> collectionReady = new ConcurrentHashMap<>();

    public QdrantTenantVectorStore(
            String qdrantUrl,
            String apiKey,
            TextEmbeddingClient embeddingClient,
            Duration timeout
    ) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.qdrantUrl = stripTrailingSlash(qdrantUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.embeddingClient = embeddingClient;
        this.timeout = timeout;
    }

    @Override
    public void upsertProfile(String collection, ProfileDocument profile) {
        String targetCollection = normalizeCollection(collection);
        List<Float> vector = embeddingClient.embedPassage(profile.profileText());
        ensureCollection(targetCollection, vector.size());
        String docId = profile.tenantId().toLowerCase() + ":" + profile.customerId();
        String pointId = UUID.nameUUIDFromBytes(docId.getBytes()).toString();
        Map<String, Object> payload = Map.of(
                "doc_id", docId,
                "tenant_id", profile.tenantId(),
                "customer_id", profile.customerId(),
                "title", profile.name(),
                "snippet", profile.profileText(),
                "source", "customer-profile",
                "reference", "profile://" + profile.customerId(),
                "updated_at", Instant.now().toString(),
                "tags", profile.tags() == null ? List.of() : profile.tags()
        );
        Map<String, Object> body = Map.of(
                "points", List.of(Map.of(
                        "id", pointId,
                        "vector", vector,
                        "payload", payload
                ))
        );
        request("PUT", "/collections/" + targetCollection + "/points?wait=true", body);
    }

    @Override
    public RagRetrieveResponse retrieve(RagRetrieveRequest request) {
        String collection = normalizeCollection(request.collection());
        List<Float> queryVector = embeddingClient.embedQuery(request.query());
        ensureCollection(collection, queryVector.size());

        Map<String, Object> body = Map.of(
                "vector", queryVector,
                "limit", Math.max(1, request.topK()),
                "with_payload", true,
                "filter", Map.of(
                        "must", List.of(Map.of(
                                "key", "tenant_id",
                                "match", Map.of("value", request.tenantId())
                        ))
                )
        );
        JsonNode root = request("POST", "/collections/" + collection + "/points/search", body);
        List<RagDocument> documents = new ArrayList<>();
        JsonNode result = root.path("result");
        if (result.isArray()) {
            for (JsonNode item : result) {
                JsonNode payload = item.path("payload");
                documents.add(new RagDocument(
                        payload.path("doc_id").asText(item.path("id").asText("")),
                        payload.path("title").asText("Untitled"),
                        payload.path("reference").asText("<unknown>"),
                        payload.path("snippet").asText(""),
                        item.path("score").asDouble(0.0),
                        payload.path("source").asText("rag")
                ));
            }
        }
        return new RagRetrieveResponse(request.tenantId(), collection, documents);
    }

    private void ensureCollection(String collection, int vectorSize) {
        if (collectionReady.putIfAbsent(collection, Boolean.TRUE) != null) {
            return;
        }
        HttpRequest get = baseRequest(URI.create(qdrantUrl + "/collections/" + collection))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(get, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
            if (response.statusCode() != 404) {
                throw new IllegalStateException("Qdrant collection check failed HTTP " + response.statusCode());
            }
            Map<String, Object> createBody = Map.of(
                    "vectors", Map.of(
                            "size", vectorSize,
                            "distance", "Cosine"
                    )
            );
            request("PUT", "/collections/" + collection, createBody);
        } catch (Exception exception) {
            collectionReady.remove(collection);
            throw new IllegalStateException("Failed to ensure Qdrant collection " + collection, exception);
        }
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = baseRequest(URI.create(qdrantUrl + path))
                    .header("Content-Type", "application/json");
            String payload = body == null ? "" : JSON.writeValueAsString(body);
            HttpRequest request = switch (method) {
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(payload)).build();
                case "DELETE" -> builder.method("DELETE", HttpRequest.BodyPublishers.ofString(payload)).build();
                default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            };
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Qdrant request failed HTTP " + response.statusCode() + " body=" + response.body());
            }
            return JSON.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant request failed for path " + path, exception);
        }
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri).timeout(timeout);
        if (!apiKey.isBlank()) {
            builder.header("api-key", apiKey);
        }
        return builder;
    }

    private static String normalizeCollection(String collection) {
        return collection == null || collection.isBlank() ? "customer_profiles" : collection;
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
