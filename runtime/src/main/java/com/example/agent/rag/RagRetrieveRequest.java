package com.example.agent.rag;

import java.util.Map;

public record RagRetrieveRequest(
        String tenantId,
        String query,
        int topK,
        String collection,
        Map<String, String> filters
) {
}
