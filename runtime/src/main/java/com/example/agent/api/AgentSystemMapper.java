package com.example.agent.api;

import com.example.agent.runtime.agent.AgentDefinition;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.runtime.agent.GatewayAgentDefinition;
import com.example.agent.runtime.agent.TaskDefinition;

final class AgentSystemMapper {
    private AgentSystemMapper() {
    }

    static AgentSystemDefinition toRuntime(AgentSystem system) {
        GatewayAgent gateway = system.entrypoint();
        GatewayAgentDefinition entrypoint = new GatewayAgentDefinition(
                gateway.name(),
                gateway.instructions(),
                gateway.tools(),
                new TaskDefinition(gateway.acceptedTask().type(), gateway.acceptedTask().maxIterations()),
                gateway.delegates()
        );
        return new AgentSystemDefinition(
                entrypoint,
                system.agents().stream()
                        .map(agent -> new AgentDefinition(agent.name(), agent.instructions(), agent.tools()))
                        .toList()
        );
    }
}
