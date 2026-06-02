package com.example.agent.tool;

public record ToolDefinition(
        String name,
        boolean sourceCapable,
        int timeoutSeconds,
        int retryAttempts,
        ToolInvoker invoker
) {
}
