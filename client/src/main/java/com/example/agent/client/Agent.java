package com.example.agent.client;

import java.util.Arrays;
import java.util.List;

public record Agent(String name, String instructions, List<String> tools) {
    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private List<String> tools = List.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder instructedBy(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        public Builder uses(String... tools) {
            this.tools = tools == null ? List.of() : Arrays.stream(tools)
                    .filter(tool -> tool != null && !tool.isBlank())
                    .toList();
            return this;
        }

        public Builder uses(List<String> tools) {
            this.tools = tools == null ? List.of() : List.copyOf(tools);
            return this;
        }

        public Agent build() {
            return new Agent(name, instructions, tools);
        }
    }
}
