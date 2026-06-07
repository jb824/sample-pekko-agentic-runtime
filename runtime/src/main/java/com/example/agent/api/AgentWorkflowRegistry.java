package com.example.agent.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

public final class AgentWorkflowRegistry {
    private final Map<String, AgentWorkflow> workflowsById;

    public AgentWorkflowRegistry(List<AgentWorkflow> workflows) {
        Map<String, AgentWorkflow> indexed = new LinkedHashMap<>();
        for (AgentWorkflow workflow : workflows == null ? List.<AgentWorkflow>of() : workflows) {
            AgentWorkflow resolved = Objects.requireNonNull(workflow);
            String workflowId = resolved.workflowId();
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalArgumentException("workflowId must not be blank: " + resolved.getClass().getName());
            }
            AgentWorkflow previous = indexed.putIfAbsent(workflowId, resolved);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate AgentWorkflow id [" + workflowId + "] for "
                        + previous.getClass().getName() + " and " + resolved.getClass().getName());
            }
        }
        this.workflowsById = Map.copyOf(indexed);
    }

    public static AgentWorkflowRegistry discover() {
        return new AgentWorkflowRegistry(ServiceLoader.load(AgentWorkflow.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    public List<AgentWorkflow> workflows() {
        return List.copyOf(workflowsById.values());
    }

    public Optional<AgentWorkflow> find(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(workflowsById.get(workflowId.trim()));
    }

    public AgentWorkflow workflow(String workflowId) {
        return find(workflowId).orElseThrow(() -> new IllegalArgumentException("Unknown AgentWorkflow id [" + workflowId + "]"));
    }

    public AgentWorkflow single() {
        if (workflowsById.isEmpty()) {
            throw new IllegalStateException("No AgentWorkflow implementation found. Register one with @AgentComponent.");
        }
        if (workflowsById.size() > 1) {
            throw new IllegalStateException("Multiple AgentWorkflow implementations found: " + workflowsById.keySet()
                    + ". Set AGENT_WORKFLOW_ID or use HTTP workflow routing.");
        }
        return workflowsById.values().iterator().next();
    }
}
