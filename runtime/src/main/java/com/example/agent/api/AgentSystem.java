package com.example.agent.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AgentSystem(GatewayAgent entrypoint, List<Agent> agents) {
    public AgentSystem {
        agents = agents == null ? List.of() : List.copyOf(agents);
        if (entrypoint == null) {
            throw new IllegalArgumentException("agent system requires an entrypoint gateway agent");
        }
        Set<String> agentNames = new HashSet<>();
        for (Agent agent : agents) {
            if (!agentNames.add(agent.name())) {
                throw new IllegalArgumentException("duplicate agent registered: " + agent.name());
            }
        }
        for (String delegate : entrypoint.delegates()) {
            if (!agentNames.contains(delegate)) {
                throw new IllegalArgumentException("gateway delegate is not registered in agent system: " + delegate);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<AgentToolDefinition> toolDefinitions() {
        Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
        for (AgentToolDefinition definition : entrypoint.toolDefinitions()) {
            definitions.putIfAbsent(definition.name(), definition);
        }
        for (Agent agent : agents) {
            for (AgentToolDefinition definition : agent.toolDefinitions()) {
                definitions.putIfAbsent(definition.name(), definition);
            }
        }
        return List.copyOf(definitions.values());
    }

    public AgentTaskDefinition taskDefinition(String name) {
        if (entrypoint.acceptedTask().name().equals(name)) {
            return entrypoint.acceptedTask();
        }
        for (Agent agent : agents) {
            for (AgentTaskDefinition task : agent.acceptedTasks()) {
                if (task.name().equals(name)) {
                    return task;
                }
            }
        }
        return null;
    }

    public static final class Builder {
        private GatewayAgent entrypoint;
        private final List<Agent> agents = new ArrayList<>();

        public Builder entrypoint(GatewayAgent entrypoint) {
            this.entrypoint = entrypoint;
            return this;
        }

        public Builder agent(Agent agent) {
            agents.add(agent);
            return this;
        }

        public Builder agents(Agent... agents) {
            if (agents != null) {
                this.agents.addAll(Arrays.asList(agents));
            }
            return this;
        }

        public AgentSystem build() {
            return new AgentSystem(entrypoint, agents);
        }
    }
}
