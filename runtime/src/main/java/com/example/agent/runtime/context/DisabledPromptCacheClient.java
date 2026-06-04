package com.example.agent.runtime.context;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DisabledPromptCacheClient implements PromptCacheClient {
    @Override
    public CompletionStage<Optional<String>> lookup(String tenantId, String workflowId, String contextHash) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
