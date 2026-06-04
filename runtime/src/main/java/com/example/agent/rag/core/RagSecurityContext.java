package com.example.agent.rag.core;

import java.util.Map;

public record RagSecurityContext(String tenantId, String userId, Map<String, String> filters) {
    public RagSecurityContext {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        userId = userId == null ? "" : userId;
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
