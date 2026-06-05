package com.example.agent.api;

import java.util.Map;

@Deprecated(forRemoval = false)
public final class AgentTask {
    private AgentTask() {
    }

    public static AgentTaskRequest.Builder of(String name) {
        return AgentTaskRequest.of(name);
    }

    public static AgentTaskRequest.Builder of(AgentTaskDefinition definition) {
        return AgentTaskRequest.of(definition);
    }

    public static AgentTaskRequest request(String name, String instructions) {
        return new AgentTaskRequest(name, instructions, Map.of());
    }
}
