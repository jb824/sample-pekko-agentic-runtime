package com.example.agent.api;

import java.util.Map;

public record Goal(String type, int maxIterations) {
    public static Builder of(Class<?> goalType) {
        return new Builder(goalType.getName());
    }

    public static Builder of(String goalType) {
        return new Builder(goalType);
    }

    public static GoalRequest request(String name, String instructions) {
        return new GoalRequest(name, instructions, Map.of());
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

        public Goal build() {
            return new Goal(type, Math.max(1, maxIterations));
        }

        public GoalDefinition definition() {
            return GoalDefinition.named(type).maxIterations(maxIterations).build();
        }
    }
}
