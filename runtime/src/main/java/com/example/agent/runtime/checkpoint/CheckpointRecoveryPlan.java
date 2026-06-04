package com.example.agent.runtime.checkpoint;

public record CheckpointRecoveryPlan(
        AgentCheckpoint checkpoint,
        ContextManifest manifest,
        boolean promptCacheLookupAllowed,
        String promptCacheKey
) {
    public boolean requiresPromptRebuild() {
        return !promptCacheLookupAllowed || promptCacheKey == null || promptCacheKey.isBlank();
    }
}
