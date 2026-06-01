package com.example.agent.runtime;

import com.example.agent.protocol.AgentRequest;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface AgentRuntimeService {
    CompletionStage<AgentResult> invoke(AgentRequest request, Duration timeout);
}
