package com.example.agent.http;

import com.example.agent.api.AgentEndpoint;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTask;

import java.util.Objects;
import java.util.function.Function;

public final class AgentHttpEndpoint {
    enum Mode {
        SYNC,
        ASYNC
    }

    private final String path;
    private final AgentSystem system;
    private final Function<AgentHttpRequest, AgentTask> taskFactory;
    private final Mode mode;

    private AgentHttpEndpoint(
            String path,
            AgentSystem system,
            Function<AgentHttpRequest, AgentTask> taskFactory,
            Mode mode
    ) {
        this.path = normalizePath(path);
        this.system = Objects.requireNonNull(system);
        this.taskFactory = Objects.requireNonNull(taskFactory);
        this.mode = Objects.requireNonNull(mode);
    }

    public static AgentHttpEndpoint sync(String path, AgentSystem system, String taskType) {
        return sync(path, system, request -> AgentTask.of(taskType).instructions(request.input()).build());
    }

    public static AgentHttpEndpoint from(AgentEndpoint endpoint) {
        return switch (endpoint.mode()) {
            case SYNC -> sync(
                    endpoint.path(),
                    endpoint.workflow().system(),
                    request -> endpoint.taskFor(request.input())
            );
            case ASYNC -> async(
                    endpoint.path(),
                    endpoint.workflow().system(),
                    request -> endpoint.taskFor(request.input())
            );
        };
    }

    public static AgentHttpEndpoint sync(
            String path,
            AgentSystem system,
            Function<AgentHttpRequest, AgentTask> taskFactory
    ) {
        return new AgentHttpEndpoint(path, system, taskFactory, Mode.SYNC);
    }

    public static AgentHttpEndpoint async(String path, AgentSystem system, String taskType) {
        return async(path, system, request -> AgentTask.of(taskType).instructions(request.input()).build());
    }

    public static AgentHttpEndpoint async(
            String path,
            AgentSystem system,
            Function<AgentHttpRequest, AgentTask> taskFactory
    ) {
        return new AgentHttpEndpoint(path, system, taskFactory, Mode.ASYNC);
    }

    String path() {
        return path;
    }

    AgentSystem system() {
        return system;
    }

    AgentTask taskFor(AgentHttpRequest request) {
        return taskFactory.apply(request);
    }

    Mode mode() {
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
