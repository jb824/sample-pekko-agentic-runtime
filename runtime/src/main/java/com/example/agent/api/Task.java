package com.example.agent.api;

@Deprecated(forRemoval = false)
public record Task(String type, int maxIterations) {
    public static Builder of(Class<?> taskType) {
        return new Builder(taskType.getName());
    }

    public static Builder of(String taskType) {
        return new Builder(taskType);
    }

    public static final class Builder {
        private final String type;
        private int maxIterations = 4;

        private Builder(String type) {
            this.type = type;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Task build() {
            return new Task(type, Math.max(1, maxIterations));
        }

        public AgentTaskDefinition definition() {
            return AgentTaskDefinition.named(type).maxIterations(maxIterations).build();
        }
    }
}
