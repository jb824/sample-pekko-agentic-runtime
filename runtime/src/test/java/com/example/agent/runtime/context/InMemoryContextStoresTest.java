package com.example.agent.runtime.context;

import com.example.agent.runtime.checkpoint.ContextArtifactRef;
import com.example.agent.runtime.checkpoint.ContextManifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryContextStoresTest {
    @Test
    void manifestStoreReturnsLatestVersionPerTenantAgentWorkflow() {
        InMemoryContextManifestStore store = new InMemoryContextManifestStore();
        ContextManifest first = manifest("tenant-a", "agent-1", "workflow-1", 1);
        ContextManifest second = manifest("tenant-a", "agent-1", "workflow-1", 2);
        ContextManifest otherTenant = manifest("tenant-b", "agent-1", "workflow-1", 3);

        store.save(first).toCompletableFuture().join();
        store.save(second).toCompletableFuture().join();
        store.save(otherTenant).toCompletableFuture().join();

        var latest = store.latest("tenant-a", "agent-1", "workflow-1").toCompletableFuture().join();

        assertTrue(latest.isPresent());
        assertEquals(2L, latest.orElseThrow().version());
        assertEquals("tenant-a", latest.orElseThrow().tenantId());
    }

    @Test
    void chunkStoreIsTenantScopedAndOrderedByChunkIndex() {
        InMemoryMemoryChunkStore store = new InMemoryMemoryChunkStore();
        store.save(chunk("tenant-a", "conversation-1", 0, 2, "third")).toCompletableFuture().join();
        store.save(chunk("tenant-a", "conversation-1", 0, 1, "second")).toCompletableFuture().join();
        store.save(chunk("tenant-b", "conversation-1", 0, 3, "wrong tenant")).toCompletableFuture().join();

        List<MemoryChunkRecord> chunks = store.recent("tenant-a", "conversation-1", 0, 10)
                .toCompletableFuture()
                .join();

        assertEquals(List.of("second", "third"), chunks.stream().map(MemoryChunkRecord::content).toList());
    }

    @Test
    void artifactStoreReturnsReferenceNotInlineCheckpointPayload() {
        InMemoryContextArtifactStore store = new InMemoryContextArtifactStore();

        ContextArtifactRef ref = store.put("tenant-a", "workflow-1", "text/plain", "large transcript")
                .toCompletableFuture()
                .join();

        assertTrue(ref.objectRef().startsWith("memory://tenant-a/workflow-1/"));
        assertEquals("large transcript", store.get(ref).toCompletableFuture().join());
    }

    private static ContextManifest manifest(String tenantId, String agentId, String workflowId, long version) {
        return new ContextManifest(
                tenantId,
                agentId,
                workflowId,
                version,
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                List.of(UUID.randomUUID()),
                new ContextArtifactRef("s3://bucket/context", "application/json", "hash", 100),
                100_000,
                Instant.EPOCH
        );
    }

    private static MemoryChunkRecord chunk(String tenantId, String conversationId, int bucket, int index, String content) {
        return new MemoryChunkRecord(
                tenantId,
                conversationId,
                bucket,
                index,
                UUID.randomUUID(),
                10,
                content,
                "hash-" + index,
                Instant.EPOCH
        );
    }
}
