package com.example.agent.rag.core;

import java.util.Map;

public record RagSearchRequest(
        RagSecurityContext securityContext,
        int topK,
        double minScore,
        Map<String, String> filters
) {
    public RagSearchRequest {
        securityContext = securityContext == null ? new RagSecurityContext("default", "", Map.of()) : securityContext;
        topK = Math.max(1, topK);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
