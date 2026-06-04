package com.example.agent.runtime.context;

import com.example.agent.runtime.checkpoint.ContextArtifactRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryContextArtifactStore implements ContextArtifactStore {
    private final Map<String, String> artifacts = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<ContextArtifactRef> put(String tenantId, String workflowId, String contentType, String content) {
        String normalized = content == null ? "" : content;
        String objectRef = "memory://" + normalizeTenant(tenantId) + "/" + workflowId + "/" + UUID.randomUUID();
        artifacts.put(objectRef, normalized);
        return CompletableFuture.completedFuture(new ContextArtifactRef(
                objectRef,
                contentType,
                sha256(normalized),
                normalized.getBytes(StandardCharsets.UTF_8).length
        ));
    }

    @Override
    public CompletionStage<String> get(ContextArtifactRef ref) {
        return CompletableFuture.completedFuture(artifacts.getOrDefault(ref.objectRef(), ""));
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
