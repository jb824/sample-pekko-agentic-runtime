package com.example.agent.runtime.context;

import com.example.agent.runtime.checkpoint.ContextManifest;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryContextManifestStore implements ContextManifestStore {
    private final Map<String, ContextManifest> manifests = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Void> save(ContextManifest manifest) {
        manifests.put(key(manifest.tenantId(), manifest.agentId(), manifest.workflowId(), manifest.version()), manifest);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Optional<ContextManifest>> latest(String tenantId, String agentId, String workflowId) {
        return CompletableFuture.completedFuture(manifests.values().stream()
                .filter(manifest -> manifest.tenantId().equals(normalizeTenant(tenantId)))
                .filter(manifest -> manifest.agentId().equals(agentId))
                .filter(manifest -> manifest.workflowId().equals(workflowId))
                .max(Comparator.comparingLong(ContextManifest::version)));
    }

    private static String key(String tenantId, String agentId, String workflowId, long version) {
        return normalizeTenant(tenantId) + "\u0000" + agentId + "\u0000" + workflowId + "\u0000" + version;
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }
}
