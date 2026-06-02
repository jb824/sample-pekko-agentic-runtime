package com.example.agent.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentSystemBuilderTest {
    @Test
    void buildsActivityPlanningSystem() {
        Agent weather = Agent.named("weather")
                .instructedBy("Answer weather questions using weather tools.")
                .uses("web.search")
                .build();

        Agent activity = Agent.named("activity")
                .instructedBy("Recommend activities using weather context and preferences.")
                .build();

        GatewayAgent planner = GatewayAgent.named("activity-planner")
                .accepts(Task.of("activity.request").maxIterations(5).build())
                .delegatesTo(weather, activity)
                .instructedBy("Coordinate the team and return concise activity options.")
                .build();

        AgentSystem system = AgentSystem.builder()
                .entrypoint(planner)
                .agents(weather, activity)
                .build();

        assertEquals("activity-planner", system.entrypoint().name());
        assertEquals(5, system.entrypoint().acceptedTask().maxIterations());
        assertEquals(2, system.agents().size());
        assertTrue(system.entrypoint().delegates().contains("weather"));
        assertTrue(system.entrypoint().delegates().contains("activity"));

        AgentComponentClient componentClient = AgentComponentClient.builder().build();
        assertEquals("task-1", componentClient.forTask("task-1").taskId());
    }
}
