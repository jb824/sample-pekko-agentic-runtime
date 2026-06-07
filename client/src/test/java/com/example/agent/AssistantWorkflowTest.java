package com.example.agent;

import com.example.agent.api.GatewayAgent;
import com.example.agent.api.http.HttpEndpoint;
import com.example.agent.api.http.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantWorkflowTest {
    @Test
    void definesClientOwnedWorkflowAndEndpoint() {
        AssistantWorkflow workflow = new AssistantWorkflow();
        assertEquals("orchestrator", workflow.system().entrypoint().name());
        assertEquals("answer.question", workflow.goalType());
        assertEquals(workflow.goalDefinition(), workflow.system().entrypoint().acceptedGoal());
        assertEquals(GatewayAgent.OrchestrationMode.WORKFLOW_DRIVEN, workflow.system().entrypoint().orchestrationMode());
        assertEquals(List.of("assistant", "reviewer"), workflow.system().entrypoint().delegates());
        assertEquals(2, workflow.system().agents().size());
        assertEquals(
                Set.of(
                        "getCurrentDate",
                        "getCurrentTime",
                        "getCurrentDateWithZoneId",
                        "getCurrentTimeWithZoneId",
                        "getCurrentDateTimeWithZoneId",
                        "getIanaZoneIds",
                        "calculate"
                ),
                Set.copyOf(workflow.system().agents().getFirst().tools())
        );
        assertEquals("/v1/assistant", AssistantHttpEndpoint.class.getAnnotation(HttpEndpoint.class).value());
        assertEquals("public", AssistantHttpEndpoint.class.getAnnotation(RolesAllowed.class).value()[0]);
    }
}
