package com.example.agent.workflow;

import com.example.agent.config.AppConfig;
import com.example.agent.tool.ToolCatalog;

import java.util.List;
import java.util.Objects;

public final class WorkflowTemplate {
    private final String name;
    private final WorkflowStyle style;
    private final List<String> explicitTools;
    private final int maxTools;
    private final int maxSteps;
    private final int maxToolRetries;

    private WorkflowTemplate(
            String name,
            WorkflowStyle style,
            List<String> explicitTools,
            int maxTools,
            int maxSteps,
            int maxToolRetries
    ) {
        this.name = Objects.requireNonNull(name).trim().toLowerCase();
        this.style = Objects.requireNonNull(style);
        this.explicitTools = explicitTools == null ? List.of() : List.copyOf(explicitTools);
        this.maxTools = maxTools;
        this.maxSteps = maxSteps;
        this.maxToolRetries = maxToolRetries;
    }

    public static WorkflowTemplate research() {
        return new WorkflowTemplate("research", WorkflowStyle.DIRECT, List.of(), 0, 1, 0);
    }

    public static WorkflowTemplate plannerExecutor() {
        return new WorkflowTemplate("planner-executor", WorkflowStyle.PLAN_AND_EXECUTE, List.of(), -1, 1, 0);
    }

    public static WorkflowTemplate react() {
        return new WorkflowTemplate("react", WorkflowStyle.REACT_LOOP, List.of(), -1, -1, -1);
    }

    public static Builder named(String name, WorkflowStyle style) {
        return new Builder(name, style);
    }

    public WorkflowSpec toSpec(AppConfig config) {
        List<String> configuredTools = ToolCatalog.parseEnabledTools(config.enabledTools());
        List<String> tools = explicitTools.isEmpty() ? configuredTools : explicitTools;
        int resolvedMaxTools = maxTools < 0 ? config.maxTools() : maxTools;
        int resolvedMaxSteps = maxSteps < 0 ? config.maxSteps() : maxSteps;
        int resolvedRetries = maxToolRetries < 0 ? config.maxToolRetries() : maxToolRetries;

        return WorkflowSpec.named(name, toEngine(style))
                .defaultTools(tools)
                .maxTools(resolvedMaxTools)
                .maxSteps(resolvedMaxSteps)
                .maxToolRetries(resolvedRetries)
                .workflowTimeout(config.workflowTimeout())
                .toolTimeout(config.toolTimeout())
                .build();
    }

    private static WorkflowEngine toEngine(WorkflowStyle style) {
        return switch (style) {
            case DIRECT -> WorkflowEngine.RESEARCH;
            case PLAN_AND_EXECUTE -> WorkflowEngine.PLANNER_EXECUTOR;
            case REACT_LOOP -> WorkflowEngine.REACT;
        };
    }

    public static final class Builder {
        private final String name;
        private final WorkflowStyle style;
        private List<String> explicitTools = List.of();
        private int maxTools = -1;
        private int maxSteps = -1;
        private int maxToolRetries = -1;

        private Builder(String name, WorkflowStyle style) {
            this.name = name;
            this.style = style;
        }

        public Builder tools(List<String> tools) {
            this.explicitTools = List.copyOf(tools);
            return this;
        }

        public Builder maxTools(int maxTools) {
            this.maxTools = maxTools;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxToolRetries(int maxToolRetries) {
            this.maxToolRetries = maxToolRetries;
            return this;
        }

        public WorkflowTemplate build() {
            return new WorkflowTemplate(name, style, explicitTools, maxTools, maxSteps, maxToolRetries);
        }
    }
}
