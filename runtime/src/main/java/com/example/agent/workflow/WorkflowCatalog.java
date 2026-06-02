package com.example.agent.workflow;

import com.example.agent.config.AppConfig;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowCatalog {
    private final Map<String, WorkflowSpec> specs;

    private WorkflowCatalog(Map<String, WorkflowSpec> specs) {
        this.specs = Map.copyOf(specs);
    }

    public static WorkflowCatalog fromConfig(AppConfig config) {
        Map<String, WorkflowSpec> values = new LinkedHashMap<>();
        values.put("research", WorkflowTemplate.research().toSpec(config));
        values.put("planner-executor", WorkflowTemplate.plannerExecutor().toSpec(config));
        values.put("react", WorkflowTemplate.react().toSpec(config));
        return new WorkflowCatalog(values);
    }

    public WorkflowSpec resolve(String workflowName) {
        String normalized = normalize(workflowName);
        WorkflowSpec spec = specs.get(normalized);
        if (spec == null) {
            throw new IllegalArgumentException("Unsupported workflow mode: " + workflowName);
        }
        return spec;
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "planner", "planner_executor" -> "planner-executor";
            case "re-act", "re_act" -> "react";
            default -> normalized;
        };
    }
}
