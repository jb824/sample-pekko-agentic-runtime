package com.example.agent.rag;

import java.util.List;

public record RagRetrieveResponse(
        String tenantId,
        String collection,
        List<RagDocument> documents
) {
}
