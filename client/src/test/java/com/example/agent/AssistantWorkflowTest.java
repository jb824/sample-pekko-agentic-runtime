package com.example.agent;

import com.example.agent.api.AgentEndpoint;
import com.example.agent.tools.SampleTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantWorkflowTest {
    @Test
    void definesClientOwnedWorkflowAndEndpoint() {
        AssistantWorkflow workflow = new AssistantWorkflow(SampleTools.timeNow(java.time.Clock.systemUTC()));
        AgentEndpoint endpoint = AssistantEndpoint.http(workflow);

        assertEquals("assistant-gateway", workflow.system().entrypoint().name());
        assertEquals("answer.question", workflow.taskType());
        assertEquals(List.of("assistant", "reviewer"), workflow.system().entrypoint().delegates());
        assertEquals(2, workflow.system().agents().size());
        assertEquals(List.of("time.now"), workflow.system().agents().getFirst().tools());
        assertEquals("/v1/assistant", endpoint.path());
        assertEquals(AgentEndpoint.Mode.ASYNC, endpoint.mode());
    }
}
