package com.example.agent;

import com.example.agent.api.AgentEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantWorkflowTest {
    @Test
    void definesClientOwnedWorkflowAndEndpoint() {
        AssistantWorkflow workflow = new AssistantWorkflow("time.now");
        AgentEndpoint endpoint = AssistantEndpoint.http(workflow);

        assertEquals("assistant-gateway", workflow.system().entrypoint().name());
        assertEquals("agent.request", workflow.taskType());
        assertEquals(List.of("time.now"), workflow.system().agents().getFirst().tools());
        assertEquals("/v1/assistant", endpoint.path());
        assertEquals(AgentEndpoint.Mode.ASYNC, endpoint.mode());
    }
}
