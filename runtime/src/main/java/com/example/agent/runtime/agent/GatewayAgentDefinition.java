package com.example.agent.runtime.agent;

import java.util.List;

public record GatewayAgentDefinition(
        String name,
        String instructions,
        List<String> tools,
        TaskDefinition acceptedTask,
        List<String> delegates
) {
    public GatewayAgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
        delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }
}
