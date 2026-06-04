package com.example.agent.runtime;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.agent.AgentSystemDefinition;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface AgentRuntimeService {
    CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystemDefinition agentSystem, Duration timeout);
}
