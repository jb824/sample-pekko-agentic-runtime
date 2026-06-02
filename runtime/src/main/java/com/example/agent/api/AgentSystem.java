package com.example.agent.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record AgentSystem(GatewayAgent entrypoint, List<Agent> agents) {
    public AgentSystem {
        agents = agents == null ? List.of() : List.copyOf(agents);
        if (entrypoint == null) {
            throw new IllegalArgumentException("agent system requires an entrypoint gateway agent");
        }
    }

    public static Builder builder() {
        return new Builder();
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
