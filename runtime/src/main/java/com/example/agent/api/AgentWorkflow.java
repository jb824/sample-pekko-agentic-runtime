package com.example.agent.api;

import java.time.Duration;

public interface AgentWorkflow {
    AgentSystem system();

    GoalDefinition goalDefinition();

    default String goalType() {
        return goalDefinition().name();
    }

    Duration timeout();

    default GoalRequest goal(String input) {
        return goalDefinition().request(input);
    }
}
