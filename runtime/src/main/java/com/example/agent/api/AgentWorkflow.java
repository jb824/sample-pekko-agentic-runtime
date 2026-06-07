package com.example.agent.api;

import com.example.agent.config.AppConfig;
import com.example.agent.runtime.consumer.AgentConsumer;

import java.time.Duration;
import java.util.List;

public interface AgentWorkflow {
    AgentSystem system();

    GoalDefinition goalDefinition();

    default String goalType() {
        return goalDefinition().name();
    }

    default String workflowId() {
        AgentComponent component = getClass().getAnnotation(AgentComponent.class);
        return component == null || component.id().isBlank()
                ? getClass().getSimpleName()
                : component.id();
    }

    Duration timeout();

    default GoalRequest goal(String input) {
        return goalDefinition().request(input);
    }

    default List<AgentConsumer> consumers(AppConfig config) {
        return List.of();
    }

    default AgentEndpoint endpoint() {
        return AgentEndpoint.async("/v1/agents", this);
    }

    static AgentWorkflow init() {
        return AgentWorkflowRegistry.discover().single();
    }

    static List<AgentWorkflow> discover() {
        return AgentWorkflowRegistry.discover().workflows();
    }
}
