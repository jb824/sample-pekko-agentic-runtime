package com.example.agent.workflow;

import com.example.agent.config.AppConfig;
import com.example.agent.tool.ToolCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowCatalog {
    private final Map<String, WorkflowSpec> specs;

    private WorkflowCatalog(Map<String, WorkflowSpec> specs) {
        this.specs = Map.copyOf(specs);
    }

    public static WorkflowCatalog fromConfig(AppConfig config) {
        List<String> configuredTools = ToolCatalog.parseEnabledTools(config.enabledTools());
        Map<String, WorkflowSpec> values = new LinkedHashMap<>();
        values.put(
                "research",
                WorkflowSpec.named("research", WorkflowEngine.RESEARCH)
                        .defaultTools(List.of())
                        .maxTools(0)
                        .maxSteps(1)
                        .maxToolRetries(0)
                        .workflowTimeout(config.workflowTimeout())
                        .toolTimeout(config.toolTimeout())
                        .build()
        );
        values.put(
                "planner-executor",
                WorkflowSpec.named("planner-executor", WorkflowEngine.PLANNER_EXECUTOR)
                        .defaultTools(configuredTools)
                        .maxTools(config.maxTools())
                        .workflowTimeout(config.workflowTimeout())
                        .toolTimeout(config.toolTimeout())
                        .build()
        );
        values.put(
                "react",
                WorkflowSpec.named("react", WorkflowEngine.REACT)
                        .defaultTools(configuredTools)
                        .maxTools(config.maxTools())
                        .maxSteps(config.maxSteps())
                        .maxToolRetries(config.maxToolRetries())
                        .workflowTimeout(config.workflowTimeout())
                        .toolTimeout(config.toolTimeout())
                        .build()
        );
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
