package com.example.agent.runtime;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.workflow.WorkflowSpec;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface WorkflowRuntimeService extends AgentRuntimeService {
    CompletionStage<AgentResult> invoke(AgentRequest request, WorkflowSpec workflowSpec, Duration timeout);

    CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystemDefinition agentSystem, Duration timeout);
}
