package com.example.agent.api;

public record AgentRunContext(String tenantId) {
    private static final String DEFAULT_TENANT_ID = "default";

    public AgentRunContext {
        tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT_ID : tenantId;
    }

    public static AgentRunContext defaults() {
        return new AgentRunContext(DEFAULT_TENANT_ID);
    }

    public static AgentRunContext tenant(String tenantId) {
        return new AgentRunContext(tenantId);
    }
}
