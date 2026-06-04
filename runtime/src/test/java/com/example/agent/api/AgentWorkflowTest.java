package com.example.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentWorkflowTest {
    @Test
    void createsTaskFromWorkflowInput() {
        AgentWorkflow workflow = new TestWorkflow();

        assertEquals("assistant-gateway", workflow.system().entrypoint().name());
        assertEquals("agent.request", workflow.taskType());
        assertEquals("hello", workflow.task("hello").instructions());
        assertEquals(1, workflow.system().agents().size());
    }

    private static final class TestWorkflow implements AgentWorkflow {
        private final AgentSystem system;

        private TestWorkflow() {
            Agent agent = Agent.named("assistant")
                    .instructedBy("Answer concisely.")
                    .uses("time.now")
                    .build();
            GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                    .accepts(Task.of("agent.request").maxIterations(1).build())
                    .delegatesTo(agent)
                    .build();
            this.system = AgentSystem.builder().entrypoint(gateway).agent(agent).build();
        }

        @Override
        public AgentSystem system() {
            return system;
        }

        @Override
        public String taskType() {
            return "agent.request";
        }

        @Override
        public java.time.Duration timeout() {
            return java.time.Duration.ofSeconds(60);
        }
    }
}
