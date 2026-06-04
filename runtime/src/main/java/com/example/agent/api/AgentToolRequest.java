package com.example.agent.api;

import java.util.Map;

public record AgentToolRequest(
        String requestId,
        String tenantId,
        String toolName,
        String userInput,
        Map<String, String> arguments
) {
    public AgentToolRequest {
        tenantId = tenantId == null || tenantId.isBlank() ? AgentRunContext.defaults().tenantId() : tenantId;
        userInput = userInput == null ? "" : userInput;
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
