package com.example.agent;

import com.example.agent.api.GatewayAgent;
import com.example.agent.api.AgentWorkflow;
import com.example.agent.api.AgentConsumerRegistry;
import com.example.agent.api.AgentWorkflowRegistry;
import com.example.agent.config.AppConfig;
import com.example.agent.api.http.HttpEndpoint;
import com.example.agent.api.http.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        "calculate",
                        "web.search",
                        "web.fetch"
                ),
                Set.copyOf(workflow.system().agents().getFirst().tools())
        );
        assertEquals("/v1/assistant", AssistantHttpEndpoint.class.getAnnotation(HttpEndpoint.class).value());
        assertEquals("public", AssistantHttpEndpoint.class.getAnnotation(RolesAllowed.class).value()[0]);
        assertEquals("/v1/assistant", workflow.endpoint().path());
    }

    @Test
    void serviceLoaderInitializesRegisteredWorkflow() {
        AgentWorkflowRegistry registry = AgentWorkflowRegistry.discover();

        assertTrue(registry.find("assistant-workflow").orElseThrow() instanceof AssistantWorkflow);
        assertTrue(registry.find("threat-tracker").orElseThrow() instanceof ThreatVulnerabilityTrackerWorkflow);
        assertThrows(IllegalStateException.class, AgentWorkflow::init);
    }

    @Test
    void discoversWorkflowScopedConsumers() {
        AgentConsumerRegistry registry = AgentConsumerRegistry.discover(AppConfig.fromEnvironment());

        assertEquals(1, registry.consumersFor("assistant-workflow").size());
        assertTrue(registry.consumersFor("assistant-workflow").getFirst() instanceof EvalConsumer);
        assertTrue(registry.consumersFor("threat-tracker").isEmpty());
    }
}
