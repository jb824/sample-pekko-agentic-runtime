package com.example.agent.rag.core;

public record RagIndexResult(String tenantId, String documentId, int chunksIndexed, boolean skipped) {
}
