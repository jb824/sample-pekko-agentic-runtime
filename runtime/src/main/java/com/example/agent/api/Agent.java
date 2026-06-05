package com.example.agent.api;

import java.util.Arrays;
import java.util.List;

public record Agent(
        String name,
        String instructions,
        List<String> tools,
        List<AgentToolDefinition> toolDefinitions,
        List<AgentTaskDefinition> acceptedTasks,
        AgentMemoryConfig memory
) {
    public Agent {
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        acceptedTasks = acceptedTasks == null ? List.of() : List.copyOf(acceptedTasks);
        memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private List<String> tools = List.of();
        private List<AgentToolDefinition> toolDefinitions = List.of();
        private List<AgentTaskDefinition> acceptedTasks = List.of();
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
            this.toolDefinitions = List.of();
            return this;
        }

        public Builder uses(AgentToolDefinition... tools) {
            this.toolDefinitions = tools == null ? List.of() : Arrays.stream(tools)
                    .filter(tool -> tool != null)
                    .toList();
            this.tools = this.toolDefinitions.stream()
                    .map(AgentToolDefinition::name)
                    .toList();
            return this;
        }

        public Builder accepts(AgentTaskDefinition... tasks) {
            this.acceptedTasks = tasks == null ? List.of() : Arrays.stream(tasks)
                    .filter(task -> task != null)
                    .toList();
            return this;
        }

        public Builder accepts(Task... tasks) {
            this.acceptedTasks = tasks == null ? List.of() : Arrays.stream(tasks)
                    .filter(task -> task != null)
                    .map(task -> AgentTaskDefinition.named(task.type()).maxIterations(task.maxIterations()).build())
                    .toList();
            return this;
        }

        public Builder memory(AgentMemoryConfig memory) {
            this.memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
            return this;
        }

        public Agent build() {
            return new Agent(name, instructions, tools, toolDefinitions, acceptedTasks, memory);
        }
    }
}
