package com.example.agent.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record GatewayAgent(
        String name,
        String instructions,
        List<String> tools,
        List<AgentToolDefinition> toolDefinitions,
        AgentMemoryConfig memory,
        AgentTaskDefinition acceptedTask,
        List<String> delegates
) {
    public GatewayAgent {
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
        delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private List<String> tools = List.of();
        private List<AgentToolDefinition> toolDefinitions = List.of();
        private AgentMemoryConfig memory = AgentMemoryConfig.defaultEnabled();
        private AgentTaskDefinition acceptedTask;
        private final List<String> delegates = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder instructedBy(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        public Builder accepts(Task task) {
            this.acceptedTask = AgentTaskDefinition.named(task.type())
                    .maxIterations(task.maxIterations())
                    .build();
            return this;
        }

        public Builder accepts(AgentTaskDefinition task) {
            this.acceptedTask = task;
            return this;
        }

        public Builder delegatesTo(Agent... agents) {
            if (agents != null) {
                Arrays.stream(agents)
                        .filter(agent -> agent != null)
                        .map(Agent::name)
                        .filter(name -> name != null && !name.isBlank())
                        .forEach(delegates::add);
            }
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

        public Builder memory(AgentMemoryConfig memory) {
            this.memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
            return this;
        }

        public GatewayAgent build() {
            if (acceptedTask == null) {
                throw new IllegalStateException("gateway agent requires an accepted task");
            }
            return new GatewayAgent(name, instructions, tools, toolDefinitions, memory, acceptedTask, delegates);
        }
    }
}
