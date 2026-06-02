package com.example.agent.api;

import java.util.Arrays;
import java.util.List;

public record GatewayAgent(
        String name,
        String instructions,
        List<String> tools,
        Task acceptedTask,
        List<String> delegates
) {
    public GatewayAgent {
        tools = tools == null ? List.of() : List.copyOf(tools);
        delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private List<String> tools = List.of();
        private Task acceptedTask = Task.of("java.lang.String").build();
        private List<String> delegates = List.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder instructedBy(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        public Builder accepts(Task task) {
            this.acceptedTask = task;
            return this;
        }

        public Builder delegatesTo(Agent... agents) {
            this.delegates = agents == null ? List.of() : Arrays.stream(agents).map(Agent::name).toList();
            return this;
        }

        public Builder uses(String... tools) {
            this.tools = tools == null ? List.of() : Arrays.stream(tools)
                    .filter(tool -> tool != null && !tool.isBlank())
                    .toList();
            return this;
        }

        public GatewayAgent build() {
            return new GatewayAgent(name, instructions, tools, acceptedTask, delegates);
        }
    }
}
