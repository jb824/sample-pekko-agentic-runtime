package com.example.agent.runtime.agent;

import java.util.List;

public record AgentSystemDefinition(GatewayAgentDefinition entrypoint, List<AgentDefinition> agents) {
    public AgentSystemDefinition {
        agents = agents == null ? List.of() : List.copyOf(agents);
        if (entrypoint == null) {
            throw new IllegalArgumentException("agent system requires an entrypoint");
        }
    }
}
