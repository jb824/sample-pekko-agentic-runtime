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
        GoalDefinition acceptedGoal,
        OrchestrationMode orchestrationMode,
        List<String> delegates
) {
    public GatewayAgent {
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        memory = memory == null ? AgentMemoryConfig.defaultEnabled() : memory;
        orchestrationMode = orchestrationMode == null ? OrchestrationMode.WORKFLOW_DRIVEN : orchestrationMode;
        delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    public enum OrchestrationMode {
        WORKFLOW_DRIVEN,
        MODEL_DRIVEN
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
        private GoalDefinition acceptedGoal;
        private OrchestrationMode orchestrationMode = OrchestrationMode.WORKFLOW_DRIVEN;
        private final List<String> delegates = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder instructedBy(String instructions) {
            this.instructions = instructions == null ? "" : instructions;
            return this;
        }

        public Builder accepts(Goal goal) {
            this.acceptedGoal = GoalDefinition.named(goal.type())
                    .maxIterations(goal.maxIterations())
                    .build();
            return this;
        }

        public Builder accepts(GoalDefinition goal) {
            this.acceptedGoal = goal;
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

        public Builder delegatesTo(String... agentNames) {
            if (agentNames != null) {
                Arrays.stream(agentNames)
                        .filter(name -> name != null && !name.isBlank())
                        .forEach(delegates::add);
            }
            return this;
        }

        public Builder workflowDriven() {
            this.orchestrationMode = OrchestrationMode.WORKFLOW_DRIVEN;
            return this;
        }

        public Builder modelDriven() {
            this.orchestrationMode = OrchestrationMode.MODEL_DRIVEN;
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

        public Builder usesTools(Object source, Object... otherSources) {
            this.toolDefinitions = FunctionTools.from(source, otherSources);
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
            if (acceptedGoal == null) {
                throw new IllegalStateException("gateway agent requires an accepted goal");
            }
            return new GatewayAgent(name, instructions, tools, toolDefinitions, memory, acceptedGoal, orchestrationMode, delegates);
        }
    }
}
