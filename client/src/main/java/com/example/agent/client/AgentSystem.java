package com.example.agent.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record AgentSystem(GatewayAgent entrypoint, List<Agent> agents) {
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
            this.agents.add(agent);
            return this;
        }

        public Builder agents(Agent... agents) {
            if (agents != null) {
                this.agents.addAll(Arrays.asList(agents));
            }
            return this;
        }

        public AgentSystem build() {
            if (entrypoint == null) {
                throw new IllegalStateException("Agent system requires an entrypoint gateway agent.");
            }
            return new AgentSystem(entrypoint, List.copyOf(agents));
        }
    }
}
