package com.example.agent.runtime.memory;

public record AgentMemoryKey(String tenantId, String agentSystemId, String agentName) {
    public AgentMemoryKey {
        tenantId = normalize(tenantId, "default");
        agentSystemId = normalize(agentSystemId, "default");
        agentName = normalize(agentName, "agent");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
