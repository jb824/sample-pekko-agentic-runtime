package com.example.agent.runtime.agent;

import com.example.agent.runtime.memory.AgentMemoryEventType;

public record MemoryDefinition(
        boolean enabled,
        int maxEvents,
        boolean rememberUserTasks,
        boolean rememberToolObservations,
        boolean rememberAgentOutputs,
        boolean rememberFinalAnswers,
        boolean rememberFailures
) {
    public boolean shouldRemember(AgentMemoryEventType type) {
        return enabled && switch (type) {
            case USER_TASK -> rememberUserTasks;
            case TOOL_OBSERVATION -> rememberToolObservations;
            case AGENT_OUTPUT -> rememberAgentOutputs;
            case FINAL_ANSWER -> rememberFinalAnswers;
            case FAILURE -> rememberFailures;
        };
    }
}
