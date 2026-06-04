package com.example.agent.runtime.context;

import com.example.agent.runtime.checkpoint.ContextManifest;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface ContextManifestStore {
    CompletionStage<Void> save(ContextManifest manifest);

    CompletionStage<Optional<ContextManifest>> latest(String tenantId, String agentId, String workflowId);
}
