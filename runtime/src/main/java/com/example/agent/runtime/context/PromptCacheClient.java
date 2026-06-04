package com.example.agent.runtime.context;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface PromptCacheClient {
    CompletionStage<Optional<String>> lookup(String tenantId, String workflowId, String contextHash);
}
