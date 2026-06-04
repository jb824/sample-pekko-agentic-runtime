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
    private final Function<String, AgentTask> taskFactory;
    private final Mode mode;

    private AgentEndpoint(
            String path,
            AgentWorkflow workflow,
            Function<String, AgentTask> taskFactory,
            Mode mode
    ) {
        this.path = normalizePath(path);
        this.workflow = Objects.requireNonNull(workflow);
        this.taskFactory = Objects.requireNonNull(taskFactory);
        this.mode = Objects.requireNonNull(mode);
    }

    public static AgentEndpoint sync(String path, AgentWorkflow workflow) {
        return sync(path, workflow, workflow::task);
    }

    public static AgentEndpoint sync(String path, AgentWorkflow workflow, Function<String, AgentTask> taskFactory) {
        return new AgentEndpoint(path, workflow, taskFactory, Mode.SYNC);
    }

    public static AgentEndpoint async(String path, AgentWorkflow workflow) {
        return async(path, workflow, workflow::task);
    }

    public static AgentEndpoint async(String path, AgentWorkflow workflow, Function<String, AgentTask> taskFactory) {
        return new AgentEndpoint(path, workflow, taskFactory, Mode.ASYNC);
    }

    public String path() {
        return path;
    }

    public AgentWorkflow workflow() {
        return workflow;
    }

    public AgentTask taskFor(String input) {
        return taskFactory.apply(input);
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
