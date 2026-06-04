package com.example.agent.api;

import java.util.List;

public record AgentToolResult(String output, List<String> sources, Throwable error) {
    public AgentToolResult {
        output = output == null ? "" : output;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static AgentToolResult success(String output) {
        return new AgentToolResult(output, List.of(), null);
    }

    public static AgentToolResult success(String output, List<String> sources) {
        return new AgentToolResult(output, sources, null);
    }

    public static AgentToolResult failure(Throwable error) {
        return new AgentToolResult("", List.of(), error);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
