package com.example.agent.rag.ingest;

import com.example.agent.config.AppConfig;
import com.example.agent.rag.service.ProfileDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.Tika;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class LocalCorpusAutoIngestor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SUPPORTED = List.of(".md", ".txt", ".pdf", ".doc", ".docx");

    private LocalCorpusAutoIngestor() {
    }

    public static void maybeIngest(org.slf4j.Logger log, AppConfig config) {
        if (!config.ragAutoIngestEnabled()) {
            return;
        }
        if (config.ragAutoIngestDirectory() == null || config.ragAutoIngestDirectory().isBlank()) {
            log.warn("RAG auto-ingest is enabled but rag.auto_ingest.directory is blank; skipping.");
            return;
        }
        Path root = Path.of(config.ragAutoIngestDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("RAG auto-ingest directory does not exist: {}", root);
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.ragServiceTimeoutMs()))
                .build();
        if (!isServiceReady(client, config)) {
            log.warn(
                    "RAG auto-ingest skipped: retrieval service is not reachable at {}/health",
                    strip(config.ragRetrievalUrl())
            );
            return;
        }
        int indexed = 0;
        int failed = 0;

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).filter(LocalCorpusAutoIngestor::supported).toList()) {
                try {
                    String text = extract(file);
                    if (text.isBlank()) {
                        continue;
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    ProfileDocument document = new ProfileDocument(
                            config.ragTenantId(),
                            "local-" + relative.replace('/', '_'),
                            relative,
                            text,
                            List.of("local", "auto-ingest")
                    );
                    ingest(client, config, document);
                    indexed++;
                } catch (Exception exception) {
                    failed++;
                    log.warn("Failed auto-ingest for {}", file, exception);
                }
            }
            log.info("RAG auto-ingest completed directory={} indexed={} failed={}", root, indexed, failed);
        } catch (IOException exception) {
            log.warn("RAG auto-ingest scan failed for {}: {}", root, exception.getMessage());
        }
    }

    private static boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED.stream().anyMatch(name::endsWith);
    }

    private static String extract(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".txt")) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        return new Tika().parseToString(file);
    }

    private static void ingest(HttpClient client, AppConfig config, ProfileDocument document) throws Exception {
        String payload = JSON.writeValueAsString(document);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(strip(config.ragRetrievalUrl()) + "/v1/ingest/profile"))
                .timeout(Duration.ofMillis(config.ragServiceTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("ingest HTTP " + response.statusCode() + " body=" + response.body());
        }
    }

    private static boolean isServiceReady(HttpClient client, AppConfig config) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(strip(config.ragRetrievalUrl()) + "/health"))
                    .timeout(Duration.ofMillis(config.ragServiceTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String strip(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
