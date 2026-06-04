package com.example.agent.runtime.checkpoint;

public enum WorkflowStep {
    ACCEPTED,
    PLANNING,
    TOOL_EXECUTION,
    DELEGATION,
    SYNTHESIS,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED
}
