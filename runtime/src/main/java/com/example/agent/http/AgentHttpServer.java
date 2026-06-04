package com.example.agent.http;

import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.model.ContentTypes;
import org.apache.pekko.http.javadsl.model.HttpEntities;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCode;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.PathMatchers;
import org.apache.pekko.http.javadsl.server.Route;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentHttpServer extends AllDirectives implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRuntime runtime;
    private final String host;
    private final int port;
    private final String healthPath;
    private final String taskStatusBasePath;
    private final List<AgentHttpEndpoint> endpoints;
    private final ActorSystem<Void> actorSystem;
    private final boolean ownsActorSystem;
    private volatile ServerBinding binding;

    private AgentHttpServer(
            AgentRuntime runtime,
            String host,
            int port,
            String healthPath,
            String taskStatusBasePath,
            List<AgentHttpEndpoint> endpoints,
            ActorSystem<Void> actorSystem,
            boolean ownsActorSystem
    ) {
        this.runtime = Objects.requireNonNull(runtime);
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.healthPath = normalizeExactPath(healthPath);
        this.taskStatusBasePath = normalizePrefix(taskStatusBasePath);
        this.endpoints = List.copyOf(endpoints);
        this.actorSystem = Objects.requireNonNull(actorSystem);
        this.ownsActorSystem = ownsActorSystem;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<ServerBinding> start() {
        return Http.get(actorSystem)
                .newServerAt(host, port)
                .bind(route())
                .thenApply(bound -> {
                    this.binding = bound;
                    return bound;
                });
    }

    public Route route() {
        List<Route> routes = new ArrayList<>();
        routes.add(path(PathMatchers.separateOnSlashes(stripLeadingSlash(healthPath)), () ->
                get(() -> complete(json(StatusCodes.OK, "{\"status\":\"UP\"}")))
        ));
        routes.add(pathPrefix(PathMatchers.separateOnSlashes(stripSlashes(taskStatusBasePath)), () ->
                path(PathMatchers.segment(), taskId ->
                        get(() -> completeWithFuture(taskStatus(taskId)))
                )
        ));
        for (AgentHttpEndpoint endpoint : endpoints) {
            routes.add(path(PathMatchers.separateOnSlashes(stripLeadingSlash(endpoint.path())), () ->
                    post(() -> extractRequest(request ->
                            completeWithFuture(request.entity().toStrict(32768, actorSystem)
                                    .thenCompose(strict -> invokeEndpoint(endpoint, strict))
                                    .exceptionally(error -> json(StatusCodes.BAD_REQUEST, errorJson(error))))
                    ))
            ));
        }
        Route route = routes.getFirst();
        for (int index = 1; index < routes.size(); index++) {
            route = concat(route, routes.get(index));
        }
        return route;
    }

    @Override
    public void close() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        ServerBinding activeBinding = this.binding;
        CompletionStage<?> unbindStage = activeBinding == null
                ? CompletableFuture.completedFuture(null)
                : activeBinding.unbind();
        unbindStage.whenComplete((ignored, failure) -> {
            if (ownsActorSystem) {
                actorSystem.terminate();
                actorSystem.getWhenTerminated().whenComplete((term, termFailure) -> {
                    if (failure != null) {
                        done.completeExceptionally(failure);
                    } else if (termFailure != null) {
                        done.completeExceptionally(termFailure);
                    } else {
                        done.complete(null);
                    }
                });
            } else if (failure != null) {
                done.completeExceptionally(failure);
            } else {
                done.complete(null);
            }
        });
        done.join();
    }

    private CompletionStage<HttpResponse> invokeEndpoint(AgentHttpEndpoint endpoint, HttpEntity.Strict strictEntity) {
        try {
            AgentHttpRequest request = JSON.readValue(strictEntity.getData().utf8String(), AgentHttpRequest.class);
            Duration timeout = request.resolvedTimeout(Duration.ofSeconds(60));
            return switch (endpoint.mode()) {
                case SYNC -> runtime.run(
                                request.resolvedRequestId(),
                                endpoint.system(),
                                endpoint.taskFor(request),
                                timeout
                        )
                        .thenApply(result -> json(StatusCodes.OK, toJson(result)));
                case ASYNC -> runtime.componentClient()
                        .forGatewayAgent(endpoint.system(), request.resolvedRequestId())
                        .runSingleTaskAsync(endpoint.taskFor(request), timeout)
                        .thenApply(taskId -> json(
                                StatusCodes.ACCEPTED,
                                toJson(new TaskAccepted(taskId, taskStatusBasePath + taskId))
                        ));
            };
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<HttpResponse> taskStatus(String taskId) {
        return runtime.componentClient().forTask(taskId).getAsync()
                .thenApply(task -> json(StatusCodes.OK, toJson(task)))
                .exceptionally(error -> json(StatusCodes.BAD_REQUEST, errorJson(error)));
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private static HttpResponse json(StatusCode status, String body) {
        return HttpResponse.create().withStatus(status).withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, body));
    }

    private static String errorJson(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return "{\"error\":\"" + safe(message) + "\"}";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }

    private static String normalizeExactPath(String path) {
        String normalized = normalizePrefix(path);
        return normalized.endsWith("/") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String normalizePrefix(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        String normalized = path.trim();
        normalized = normalized.startsWith("/") ? normalized : "/" + normalized;
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static String stripLeadingSlash(String path) {
        String normalized = path == null ? "" : path.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String stripSlashes(String path) {
        String normalized = stripLeadingSlash(path);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record TaskAccepted(String taskId, String statusUrl) {
    }

    public static final class Builder {
        private AgentRuntime runtime;
        private String host = "0.0.0.0";
        private int port = 8080;
        private String healthPath = "/health";
        private String taskStatusBasePath = "/v1/agents/tasks";
        private final List<AgentHttpEndpoint> endpoints = new ArrayList<>();
        private ActorSystem<Void> actorSystem;

        public Builder runtime(AgentRuntime runtime) {
            this.runtime = Objects.requireNonNull(runtime);
            return this;
        }

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host);
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder healthPath(String healthPath) {
            this.healthPath = Objects.requireNonNull(healthPath);
            return this;
        }

        public Builder taskStatusBasePath(String taskStatusBasePath) {
            this.taskStatusBasePath = Objects.requireNonNull(taskStatusBasePath);
            return this;
        }

        public Builder endpoint(AgentHttpEndpoint endpoint) {
            this.endpoints.add(Objects.requireNonNull(endpoint));
            return this;
        }

        public Builder endpoint(AgentEndpoint endpoint) {
            return endpoint(AgentHttpEndpoint.from(endpoint));
        }

        public Builder syncEndpoint(String path, com.example.agent.api.AgentWorkflow workflow) {
            return endpoint(AgentEndpoint.sync(path, workflow));
        }

        public Builder asyncEndpoint(String path, com.example.agent.api.AgentWorkflow workflow) {
            return endpoint(AgentEndpoint.async(path, workflow));
        }

        public Builder syncEndpoint(String path, com.example.agent.api.AgentSystem system, String taskType) {
            return endpoint(AgentHttpEndpoint.sync(path, system, taskType));
        }

        public Builder asyncEndpoint(String path, com.example.agent.api.AgentSystem system, String taskType) {
            return endpoint(AgentHttpEndpoint.async(path, system, taskType));
        }

        public Builder actorSystem(ActorSystem<Void> actorSystem) {
            this.actorSystem = Objects.requireNonNull(actorSystem);
            return this;
        }

        public AgentHttpServer build() {
            if (runtime == null) {
                throw new IllegalStateException("runtime is required");
            }
            boolean ownsActorSystem = actorSystem == null;
            ActorSystem<Void> resolvedSystem = actorSystem == null
                    ? ActorSystem.create(Behaviors.empty(), "pekko-agent-http-server")
                    : actorSystem;
            return new AgentHttpServer(
                    runtime,
                    host,
                    port,
                    healthPath,
                    taskStatusBasePath,
                    endpoints,
                    resolvedSystem,
                    ownsActorSystem
            );
        }
    }
}
