package com.example.agent.runtime.agent;

public record TaskDefinition(String type, int maxIterations) {
    public int resolvedMaxIterations() {
        return Math.max(1, maxIterations);
    }
}
