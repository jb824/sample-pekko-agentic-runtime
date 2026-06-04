package com.example.agent.api;

import java.time.Duration;

public interface AgentWorkflow {
    AgentSystem system();

    String taskType();

    Duration timeout();

    default AgentTask task(String input) {
        return AgentTask.of(taskType()).instructions(input).build();
    }
}
