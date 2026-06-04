package com.example.agent.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEndpointTest {
    @Test
    void createsAsyncEndpointForWorkflow() {
        AgentWorkflow workflow = workflow();

        AgentEndpoint endpoint = AgentEndpoint.async("v1/assistant", workflow);

        assertEquals("/v1/assistant", endpoint.path());
        assertEquals(AgentEndpoint.Mode.ASYNC, endpoint.mode());
        assertEquals(workflow, endpoint.workflow());
        assertEquals("hello", endpoint.taskFor("hello").instructions());
    }

    private static AgentWorkflow workflow() {
        Agent agent = Agent.named("assistant").build();
        GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                .accepts(Task.of("agent.request").build())
                .delegatesTo(agent)
                .build();
        AgentSystem system = AgentSystem.builder().entrypoint(gateway).agent(agent).build();
        return new AgentWorkflow() {
            @Override
            public AgentSystem system() {
                return system;
            }

            @Override
            public String taskType() {
                return "agent.request";
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(60);
            }
        };
    }
}
