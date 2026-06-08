package com.example.agent.runtime;

import com.example.agent.api.AgentSystem;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResult;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

// Internal runtime boundary for invoking an AgentSystem without exposing actor mechanics.
public interface AgentRuntimeInvoker {
    CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystem agentSystem, Duration timeout);
}
