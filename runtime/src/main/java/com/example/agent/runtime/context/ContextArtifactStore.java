package com.example.agent.runtime.context;

import com.example.agent.runtime.checkpoint.ContextArtifactRef;

import java.util.concurrent.CompletionStage;

public interface ContextArtifactStore {
    CompletionStage<ContextArtifactRef> put(String tenantId, String workflowId, String contentType, String content);

    CompletionStage<String> get(ContextArtifactRef ref);
}
