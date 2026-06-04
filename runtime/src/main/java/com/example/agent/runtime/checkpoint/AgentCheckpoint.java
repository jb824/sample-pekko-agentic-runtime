package com.example.agent.runtime.checkpoint;

import java.util.List;
import java.util.UUID;

public record AgentCheckpoint(
        String tenantId,
        String workflowId,
        String conversationId,
        String agentId,
        WorkflowStep currentStep,
        String plan,
        List<TaskExecutionRecord> tasks,
        List<ToolResultRef> toolResults,
        UUID summaryId,
        String contextManifestRef,
        String lastProcessedEventId,
        int retryCount,
        int budgetUsed,
        String humanApprovalState,
        long lastEventSeqNr
) {
    public AgentCheckpoint {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        workflowId = workflowId == null ? "" : workflowId;
        conversationId = conversationId == null ? "" : conversationId;
        agentId = agentId == null ? "" : agentId;
        currentStep = currentStep == null ? WorkflowStep.ACCEPTED : currentStep;
        plan = plan == null ? "" : plan;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        contextManifestRef = contextManifestRef == null ? "" : contextManifestRef;
        lastProcessedEventId = lastProcessedEventId == null ? "" : lastProcessedEventId;
        retryCount = Math.max(0, retryCount);
        budgetUsed = Math.max(0, budgetUsed);
        humanApprovalState = humanApprovalState == null ? "" : humanApprovalState;
        lastEventSeqNr = Math.max(0L, lastEventSeqNr);
    }

    public static AgentCheckpoint initial(String tenantId, String workflowId, String conversationId, String agentId) {
        return new AgentCheckpoint(
                tenantId,
                workflowId,
                conversationId,
                agentId,
                WorkflowStep.ACCEPTED,
                "",
                List.of(),
                List.of(),
                null,
                "",
                "",
                0,
                0,
                "",
                0L
        );
    }

    public AgentCheckpoint withStep(WorkflowStep step) {
        return new AgentCheckpoint(
                tenantId,
                workflowId,
                conversationId,
                agentId,
                step,
                plan,
                tasks,
                toolResults,
                summaryId,
                contextManifestRef,
                lastProcessedEventId,
                retryCount,
                budgetUsed,
                humanApprovalState,
                lastEventSeqNr
        );
    }

    public AgentCheckpoint withPlan(String plan) {
        return new AgentCheckpoint(
                tenantId,
                workflowId,
                conversationId,
                agentId,
                currentStep,
                plan,
                tasks,
                toolResults,
                summaryId,
                contextManifestRef,
                lastProcessedEventId,
                retryCount,
                budgetUsed,
                humanApprovalState,
                lastEventSeqNr
        );
    }

    public AgentCheckpoint withContextManifestRef(String contextManifestRef) {
        return new AgentCheckpoint(
                tenantId,
                workflowId,
                conversationId,
                agentId,
                currentStep,
                plan,
                tasks,
                toolResults,
                summaryId,
                contextManifestRef,
                lastProcessedEventId,
                retryCount,
                budgetUsed,
                humanApprovalState,
                lastEventSeqNr
        );
    }

    public AgentCheckpoint withLastEventSeqNr(long lastEventSeqNr) {
        return new AgentCheckpoint(
                tenantId,
                workflowId,
                conversationId,
                agentId,
                currentStep,
                plan,
                tasks,
                toolResults,
                summaryId,
                contextManifestRef,
                lastProcessedEventId,
                retryCount,
                budgetUsed,
                humanApprovalState,
                lastEventSeqNr
        );
    }
}
