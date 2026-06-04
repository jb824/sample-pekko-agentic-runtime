package com.example.agent.api;

import java.util.Arrays;
import java.util.List;

public record Agent(String name, String instructions, List<String> tools, AgentMemoryConfig memory) {
    public Agent {
        tools = tools == null ? List.of() : List.copyOf(tools);
        memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private List<String> tools = List.of();
        private AgentMemoryConfig memory = AgentMemoryConfig.defaultEnabled();

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

        public Builder memory(AgentMemoryConfig memory) {
            this.memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
            return this;
        }

        public Agent build() {
            return new Agent(name, instructions, tools, memory);
        }
    }
}
