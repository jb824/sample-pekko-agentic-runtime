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
        assertEquals("hello", endpoint.goalFor("hello").instructions());
    }

    private static AgentWorkflow workflow() {
        GoalDefinition task = GoalDefinition.named("agent.request").build();
        Agent agent = Agent.named("assistant").build();
        GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                .accepts(task)
                .delegatesTo(agent)
                .build();
        AgentSystem system = AgentSystem.builder().entrypoint(gateway).agent(agent).build();
        return new AgentWorkflow() {
            @Override
            public AgentSystem system() {
                return system;
            }

            @Override
            public GoalDefinition goalDefinition() {
                return task;
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(60);
            }
        };
    }
}
