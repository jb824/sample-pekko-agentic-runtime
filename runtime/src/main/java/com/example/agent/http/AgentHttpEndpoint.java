package com.example.agent.http;

import com.example.agent.api.AgentEndpoint;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalRequest;

import java.util.Objects;
import java.util.function.Function;

public final class AgentHttpEndpoint {
    enum Mode {
        SYNC,
        ASYNC
    }

    private final String path;
    private final AgentSystem system;
    private final Function<AgentHttpRequest, GoalRequest> goalFactory;
    private final Mode mode;

    private AgentHttpEndpoint(
            String path,
            AgentSystem system,
            Function<AgentHttpRequest, GoalRequest> goalFactory,
            Mode mode
    ) {
        this.path = normalizePath(path);
        this.system = Objects.requireNonNull(system);
        this.goalFactory = Objects.requireNonNull(goalFactory);
        this.mode = Objects.requireNonNull(mode);
    }

    public static AgentHttpEndpoint sync(String path, AgentSystem system, String goalType) {
        return sync(path, system, request -> GoalRequest.of(goalType).instructions(request.input()).build());
    }

    public static AgentHttpEndpoint from(AgentEndpoint endpoint) {
        return switch (endpoint.mode()) {
            case SYNC -> sync(
                    endpoint.path(),
                    endpoint.workflow().system(),
                    request -> endpoint.goalFor(request.input())
            );
            case ASYNC -> async(
                    endpoint.path(),
                    endpoint.workflow().system(),
                    request -> endpoint.goalFor(request.input())
            );
        };
    }

    public static AgentHttpEndpoint sync(
            String path,
            AgentSystem system,
            Function<AgentHttpRequest, GoalRequest> goalFactory
    ) {
        return new AgentHttpEndpoint(path, system, goalFactory, Mode.SYNC);
    }

    public static AgentHttpEndpoint async(String path, AgentSystem system, String goalType) {
        return async(path, system, request -> GoalRequest.of(goalType).instructions(request.input()).build());
    }

    public static AgentHttpEndpoint async(
            String path,
            AgentSystem system,
            Function<AgentHttpRequest, GoalRequest> goalFactory
    ) {
        return new AgentHttpEndpoint(path, system, goalFactory, Mode.ASYNC);
    }

    String path() {
        return path;
    }

    AgentSystem system() {
        return system;
    }

    GoalRequest goalFor(AgentHttpRequest request) {
        return goalFactory.apply(request);
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
