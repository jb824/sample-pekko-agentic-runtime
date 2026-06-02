package com.example.agent.runtime.agent;

import java.util.List;

public record AgentDefinition(String name, String instructions, List<String> tools) {
    public AgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
