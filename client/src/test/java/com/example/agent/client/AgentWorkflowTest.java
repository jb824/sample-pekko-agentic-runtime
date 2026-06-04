package com.example.agent.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentWorkflowTest {
    @Test
    void createsSingleAgentWorkflow() {
        AgentWorkflow workflow = AgentWorkflow.singleAgent(
                "assistant",
                "Answer concisely.",
                "agent.request",
                "time.now"
        );

        assertEquals("assistant-gateway", workflow.system().entrypoint().name());
        assertEquals("agent.request", workflow.taskType());
        assertEquals(1, workflow.system().agents().size());
    }
}
