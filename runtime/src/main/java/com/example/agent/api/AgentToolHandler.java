package com.example.agent.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AgentToolHandler {
    CompletionStage<AgentToolResult> invoke(AgentToolRequest request);
}
