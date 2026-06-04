package com.example.agent.protocol;

public record AgentRequest(String requestId, String input, String tenantId) {
    public AgentRequest(String requestId, String input) {
        this(requestId, input, "default");
    }

    public AgentRequest {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }
}
