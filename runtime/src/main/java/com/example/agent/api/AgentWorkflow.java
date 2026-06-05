package com.example.agent.api;

import java.time.Duration;

public interface AgentWorkflow {
    AgentSystem system();

    AgentTaskDefinition taskDefinition();

    default String taskType() {
        return taskDefinition().name();
    }

    Duration timeout();

    default AgentTaskRequest task(String input) {
        return taskDefinition().request(input);
    }
}
