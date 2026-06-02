package com.example.agent.workflow;

import java.time.Duration;
import java.util.List;

public record WorkflowSpec(
        String name,
        WorkflowEngine engine,
        List<String> defaultTools,
        int maxTools,
        int maxSteps,
        int maxToolRetries,
        Duration workflowTimeout,
        Duration toolTimeout
) {
    public static Builder named(String name, WorkflowEngine engine) {
        return new Builder(name, engine);
    }

    public static final class Builder {
        private final String name;
        private final WorkflowEngine engine;
        private List<String> defaultTools = List.of();
        private int maxTools = 3;
        private int maxSteps = 4;
        private int maxToolRetries = 2;
        private Duration workflowTimeout = Duration.ofSeconds(180);
        private Duration toolTimeout = Duration.ofSeconds(30);

        private Builder(String name, WorkflowEngine engine) {
            this.name = name;
            this.engine = engine;
        }

        public Builder defaultTools(List<String> defaultTools) {
            this.defaultTools = List.copyOf(defaultTools);
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

        public Builder workflowTimeout(Duration workflowTimeout) {
            this.workflowTimeout = workflowTimeout;
            return this;
        }

        public Builder toolTimeout(Duration toolTimeout) {
            this.toolTimeout = toolTimeout;
            return this;
        }

        public WorkflowSpec build() {
            return new WorkflowSpec(
                    name,
                    engine,
                    defaultTools,
                    Math.max(0, maxTools),
                    Math.max(1, maxSteps),
                    Math.max(0, maxToolRetries),
                    workflowTimeout,
                    toolTimeout
            );
        }
    }
}
