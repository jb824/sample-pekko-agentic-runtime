package com.example.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentWorkflowTest {
    @Test
    void createsTaskFromWorkflowInput() {
        AgentWorkflow workflow = new TestWorkflow();

        assertEquals("assistant-gateway", workflow.system().entrypoint().name());
        assertEquals("agent.request", workflow.goalType());
        assertEquals("Goal: hello", workflow.goal("hello").instructions());
        assertEquals(workflow.goalDefinition(), workflow.system().goalDefinition("agent.request"));
        assertEquals(1, workflow.system().agents().size());
    }

    @Test
    void rejectsGatewayDelegateThatIsNotRegistered() {
        GoalDefinition task = GoalDefinition.named("agent.request").build();
        Agent assistant = Agent.named("assistant").build();
        GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                .accepts(task)
                .delegatesTo(assistant)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSystem.builder().entrypoint(gateway).build()
        );
    }

    @Test
    void gatewayRequiresExplicitAcceptedTask() {
        assertThrows(
                IllegalStateException.class,
                () -> GatewayAgent.named("assistant-gateway").build()
        );
    }

    private static final class TestWorkflow implements AgentWorkflow {
        private static final GoalDefinition TASK = GoalDefinition.named("agent.request")
                .describedAs("Handle an agent request.")
                .template("Goal: {{input}}")
                .maxIterations(1)
                .build();
        private final AgentSystem system;

        private TestWorkflow() {
            Agent agent = Agent.named("assistant")
                    .instructedBy("Answer concisely.")
                    .uses("time.now")
                    .build();
            GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                    .accepts(TASK)
                    .delegatesTo(agent)
                    .build();
            this.system = AgentSystem.builder().entrypoint(gateway).agent(agent).build();
        }

        @Override
        public AgentSystem system() {
            return system;
        }

        @Override
        public GoalDefinition goalDefinition() {
            return TASK;
        }

        @Override
        public java.time.Duration timeout() {
            return java.time.Duration.ofSeconds(60);
        }
    }
}
