package com.example.agent.rag.service;

import com.example.agent.config.AppConfig;
import com.example.agent.rag.RagRetrieveRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

public final class RagRetrievalServiceMain {
    private static final Logger LOG = LoggerFactory.getLogger(RagRetrievalServiceMain.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private RagRetrievalServiceMain() {
    }

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.fromEnvironment();
        int port = config.ragServicePort();
        Duration timeout = Duration.ofMillis(config.ragServiceTimeoutMs());

        TextEmbeddingClient embeddingClient;
        if ("onnx".equalsIgnoreCase(config.ragEmbeddingMode())) {
            embeddingClient = new OnnxE5EmbeddingClient(
                    config.ragEmbeddingOnnxModelUri(),
                    config.ragEmbeddingTokenizerUri()
            );
        } else {
            embeddingClient = new OpenAiCompatibleEmbeddingClient(
                    config.ragEmbeddingUrl(),
                    config.ragEmbeddingApiKey(),
                    config.ragEmbeddingModel(),
                    timeout
            );
        }
        TenantVectorStore store = new QdrantTenantVectorStore(
                config.qdrantUrl(),
                config.qdrantApiKey(),
                embeddingClient,
                timeout
        );

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/v1/ingest/profile", exchange -> handleProfileIngest(exchange, store, config.ragCollection()));
        server.createContext("/v1/retrieve", exchange -> handleRetrieve(exchange, store, config.ragCollection()));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        LOG.info(
                "RAG retrieval service listening on :{} embedding_mode={} collection={} qdrant_url={}",
                port,
                config.ragEmbeddingMode(),
                config.ragCollection(),
                config.qdrantUrl()
        );
    }

    private static void handleProfileIngest(HttpExchange exchange, TenantVectorStore store, String defaultCollection) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            ProfileDocument profile = JSON.readValue(readBody(exchange), ProfileDocument.class);
            if (profile.tenantId() == null || profile.tenantId().isBlank()
                    || profile.customerId() == null || profile.customerId().isBlank()
                    || profile.profileText() == null || profile.profileText().isBlank()) {
                respond(exchange, 400, "{\"error\":\"invalid_profile_payload\"}");
                return;
            }
            LOG.info(
                    "rag.ingest request tenant={} customer={} title='{}' profile_chars={} collection={}",
                    profile.tenantId(),
                    profile.customerId(),
                    safe(profile.name()),
                    profile.profileText() == null ? 0 : profile.profileText().length(),
                    defaultCollection
            );
            store.upsertProfile(defaultCollection, profile);
            respond(exchange, 200, "{\"status\":\"indexed\"}");
            LOG.info("rag.ingest indexed tenant={} customer={}", profile.tenantId(), profile.customerId());
        } catch (Exception exception) {
            LOG.warn("rag.ingest failed: {}", exception.toString(), exception);
            respond(exchange, 500, "{\"error\":\"ingest_failed\",\"message\":\"" + safe(exception.getMessage()) + "\"}");
        }
    }

    private static void handleRetrieve(HttpExchange exchange, TenantVectorStore store, String defaultCollection) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            RagRetrieveRequest request = JSON.readValue(readBody(exchange), RagRetrieveRequest.class);
            if (request.tenantId() == null || request.tenantId().isBlank()
                    || request.query() == null || request.query().isBlank()) {
                respond(exchange, 400, "{\"error\":\"tenant_id_and_query_required\"}");
                return;
            }
            int topK = Math.max(1, Math.min(20, request.topK()));
            RagRetrieveRequest normalized = new RagRetrieveRequest(
                    request.tenantId(),
                    request.query(),
                    topK,
                    request.collection() == null || request.collection().isBlank()
                            ? defaultCollection
                            : request.collection(),
                    request.filters() == null ? java.util.Map.of() : request.filters()
            );
            LOG.info(
                    "rag.retrieve request tenant={} collection={} topK={} query='{}'",
                    normalized.tenantId(),
                    normalized.collection(),
                    normalized.topK(),
                    truncate(normalized.query(), 240)
            );
            var response = store.retrieve(normalized);
            LOG.info(
                    "rag.retrieve response tenant={} collection={} docs={}",
                    response.tenantId(),
                    response.collection(),
                    response.documents() == null ? 0 : response.documents().size()
            );
            respond(exchange, 200, JSON.writeValueAsString(response));
        } catch (Exception exception) {
            LOG.warn("rag.retrieve failed: {}", exception.toString(), exception);
            respond(exchange, 500, "{\"error\":\"retrieve_failed\",\"message\":\"" + safe(exception.getMessage()) + "\"}");
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            return stream.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
        exchange.sendResponseHeaders(statusCode, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "'");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
