package com.example.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkflowTest {
    @Test
    void createsTaskFromWorkflowInput() {
        AgentWorkflow workflow = new TestWorkflow();

        assertEquals("assistant-gateway", workflow.system().entrypoint().name());
        assertEquals("agent.request", workflow.goalType());
        assertEquals("Goal: hello", workflow.goal("hello").instructions());
        assertEquals(workflow.goalDefinition(), workflow.system().goalDefinition("agent.request"));
        assertEquals(1, workflow.system().agents().size());
        assertEquals("TestWorkflow", workflow.workflowId());
        assertTrue(workflow.consumers(com.example.agent.config.AppConfig.fromEnvironment()).isEmpty());
        assertEquals("/v1/agents", workflow.endpoint().path());
    }

    @Test
    void discoversNoWorkflowWhenNoServiceProviderIsRegisteredInRuntimeTests() {
        List<AgentWorkflow> workflows = AgentWorkflow.discover();

        assertTrue(workflows.isEmpty());
        assertThrows(IllegalStateException.class, AgentWorkflow::init);
    }

    @Test
    void registrySelectsWorkflowByIdAndRejectsDuplicateIds() {
        TestWorkflow workflow = new TestWorkflow();
        AgentWorkflowRegistry registry = new AgentWorkflowRegistry(List.of(workflow));

        assertEquals(workflow, registry.single());
        assertEquals(workflow, registry.workflow("TestWorkflow"));
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowRegistry(List.of(workflow, workflow)));
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
