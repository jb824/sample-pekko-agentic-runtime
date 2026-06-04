package com.example.agent;

import com.example.agent.api.AgentEndpoint;

public final class AssistantEndpoint {
    private AssistantEndpoint() {
    }

    public static AgentEndpoint http(AssistantWorkflow workflow) {
        return AgentEndpoint.async("/v1/assistant", workflow);
    }
}
