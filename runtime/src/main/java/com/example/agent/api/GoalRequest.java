package com.example.agent.api;

import java.util.Map;

public record GoalRequest(String name, String instructions, Map<String, String> metadata) {
    public GoalRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("task name must not be blank");
        }
        instructions = instructions == null ? "" : instructions;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String type() {
        return name;
    }

    public static Builder of(String name) {
        return new Builder(name);
    }

    public static Builder of(GoalDefinition definition) {
        return new Builder(definition.name());
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private Map<String, String> metadata = Map.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            return this;
        }

        public GoalRequest build() {
            return new GoalRequest(name, instructions, metadata);
        }
    }
}
