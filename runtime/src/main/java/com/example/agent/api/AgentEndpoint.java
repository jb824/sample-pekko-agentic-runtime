package com.example.agent.api;

import java.util.Objects;
import java.util.function.Function;

public final class AgentEndpoint {
    public enum Mode {
        SYNC,
        ASYNC
    }

    private final String path;
    private final AgentWorkflow workflow;
    private final Function<String, GoalRequest> goalFactory;
    private final Mode mode;

    private AgentEndpoint(
            String path,
            AgentWorkflow workflow,
            Function<String, GoalRequest> goalFactory,
            Mode mode
    ) {
        this.path = normalizePath(path);
        this.workflow = Objects.requireNonNull(workflow);
        this.goalFactory = Objects.requireNonNull(goalFactory);
        this.mode = Objects.requireNonNull(mode);
    }

    public static AgentEndpoint sync(String path, AgentWorkflow workflow) {
        return sync(path, workflow, workflow::goal);
    }

    public static AgentEndpoint sync(String path, AgentWorkflow workflow, Function<String, GoalRequest> goalFactory) {
        return new AgentEndpoint(path, workflow, goalFactory, Mode.SYNC);
    }

    public static AgentEndpoint async(String path, AgentWorkflow workflow) {
        return async(path, workflow, workflow::goal);
    }

    public static AgentEndpoint async(String path, AgentWorkflow workflow, Function<String, GoalRequest> goalFactory) {
        return new AgentEndpoint(path, workflow, goalFactory, Mode.ASYNC);
    }

    public String path() {
        return path;
    }

    public AgentWorkflow workflow() {
        return workflow;
    }

    public GoalRequest goalFor(String input) {
        return goalFactory.apply(input);
    }

    public Mode mode() {
        return mode;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("endpoint path must not be blank");
        }
        String normalized = path.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
