package com.example.agent.rag.service;

import java.util.List;

public record ProfileDocument(
        String tenantId,
        String customerId,
        String name,
        String profileText,
        List<String> tags
) {
}
