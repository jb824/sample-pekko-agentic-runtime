package com.example.agent.client;

public record AgentTask(String type, String instructions) {
    public static Builder of(String type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final String type;
        private String instructions = "";

        private Builder(String type) {
            this.type = type;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        public AgentTask build() {
            return new AgentTask(type, instructions);
        }
    }
}
