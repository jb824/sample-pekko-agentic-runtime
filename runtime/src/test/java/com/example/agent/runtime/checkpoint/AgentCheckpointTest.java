package com.example.agent.runtime.checkpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentCheckpointTest {
    @Test
    void checkpointCarriesRecoveryPointersWithoutHeavyContextPayloads() {
        AgentCheckpoint checkpoint = AgentCheckpoint.initial("tenant-a", "workflow-1", "conversation-1", "agent-1")
                .withPlan("call tools then synthesize")
                .withContextManifestRef("agent_context/context_manifest_by_agent/tenant-a/agent-1/workflow-1/1")
                .withStep(WorkflowStep.TOOL_EXECUTION)
                .withLastEventSeqNr(42L);

        assertEquals("tenant-a", checkpoint.tenantId());
        assertEquals("workflow-1", checkpoint.workflowId());
        assertEquals("conversation-1", checkpoint.conversationId());
        assertEquals("agent-1", checkpoint.agentId());
        assertEquals(WorkflowStep.TOOL_EXECUTION, checkpoint.currentStep());
        assertEquals("call tools then synthesize", checkpoint.plan());
        assertTrue(checkpoint.contextManifestRef().contains("context_manifest_by_agent"));
        assertEquals(42L, checkpoint.lastEventSeqNr());
    }

    @Test
    void promptCacheMissRequiresPromptRebuild() {
        AgentCheckpoint checkpoint = AgentCheckpoint.initial("tenant-a", "workflow-1", "conversation-1", "agent-1");
        CheckpointRecoveryPlan recoveryPlan = new CheckpointRecoveryPlan(checkpoint, null, true, "");

        assertTrue(recoveryPlan.requiresPromptRebuild());
    }
}
