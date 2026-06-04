package com.example.agent.api;

import java.util.Objects;

public record AgentMemoryConfig(
        boolean enabled,
        int maxEvents,
        boolean rememberUserTasks,
        boolean rememberToolObservations,
        boolean rememberAgentOutputs,
        boolean rememberFinalAnswers,
        boolean rememberFailures
) {
    private static final int DEFAULT_MAX_EVENTS = 20;

    public AgentMemoryConfig {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
    }

    public static AgentMemoryConfig defaultEnabled() {
        return recentEvents(DEFAULT_MAX_EVENTS);
    }

    public static AgentMemoryConfig disabled() {
        return new AgentMemoryConfig(false, DEFAULT_MAX_EVENTS, false, false, false, false, false);
    }

    public static AgentMemoryConfig recentEvents(int maxEvents) {
        return new AgentMemoryConfig(true, maxEvents, true, true, true, true, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean shouldRemember(MemoryEventType type) {
        Objects.requireNonNull(type);
        return enabled && switch (type) {
            case USER_TASK -> rememberUserTasks;
            case TOOL_OBSERVATION -> rememberToolObservations;
            case AGENT_OUTPUT -> rememberAgentOutputs;
            case FINAL_ANSWER -> rememberFinalAnswers;
            case FAILURE -> rememberFailures;
        };
    }

    public enum MemoryEventType {
        USER_TASK,
        TOOL_OBSERVATION,
        AGENT_OUTPUT,
        FINAL_ANSWER,
        FAILURE
    }

    public static final class Builder {
        private boolean enabled = true;
        private int maxEvents = DEFAULT_MAX_EVENTS;
        private boolean rememberUserTasks = true;
        private boolean rememberToolObservations = true;
        private boolean rememberAgentOutputs = true;
        private boolean rememberFinalAnswers = true;
        private boolean rememberFailures = true;

        private Builder() {
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder maxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
            return this;
        }

        public Builder rememberUserTasks(boolean rememberUserTasks) {
            this.rememberUserTasks = rememberUserTasks;
            return this;
        }

        public Builder rememberToolObservations(boolean rememberToolObservations) {
            this.rememberToolObservations = rememberToolObservations;
            return this;
        }

        public Builder rememberAgentOutputs(boolean rememberAgentOutputs) {
            this.rememberAgentOutputs = rememberAgentOutputs;
            return this;
        }

        public Builder rememberFinalAnswers(boolean rememberFinalAnswers) {
            this.rememberFinalAnswers = rememberFinalAnswers;
            return this;
        }

        public Builder rememberFailures(boolean rememberFailures) {
            this.rememberFailures = rememberFailures;
            return this;
        }

        public AgentMemoryConfig build() {
            return new AgentMemoryConfig(
                    enabled,
                    maxEvents,
                    rememberUserTasks,
                    rememberToolObservations,
                    rememberAgentOutputs,
                    rememberFinalAnswers,
                    rememberFailures
            );
        }
    }
}
