package com.example.agent.runtime.context;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MemoryChunkStore {
    CompletionStage<Void> save(MemoryChunkRecord chunk);

    CompletionStage<List<MemoryChunkRecord>> recent(String tenantId, String conversationId, int bucket, int limit);

    CompletionStage<List<MemoryChunkRecord>> findByIds(String tenantId, String conversationId, List<UUID> chunkIds);
}
