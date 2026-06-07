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
import org.apache.pekko.http.javadsl.model.HttpMethods;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCode;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.PathMatchers;
import org.apache.pekko.http.javadsl.server.Route;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentHttpServer extends AllDirectives implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRuntime runtime;
    private final String host;
    private final int port;
    private final String healthPath;
    private final String goalStatusBasePath;
    private final List<AgentHttpEndpoint> endpoints;
    private final List<EndpointDescriptor> annotatedEndpoints;
    private final PrincipalExtractor principalExtractor;
    private final ActorSystem<Void> actorSystem;
    private final boolean ownsActorSystem;
    private volatile ServerBinding binding;

    private AgentHttpServer(
            AgentRuntime runtime,
            String host,
            int port,
            String healthPath,
            String goalStatusBasePath,
            List<AgentHttpEndpoint> endpoints,
            List<EndpointDescriptor> annotatedEndpoints,
            PrincipalExtractor principalExtractor,
            ActorSystem<Void> actorSystem,
            boolean ownsActorSystem
    ) {
        this.runtime = Objects.requireNonNull(runtime);
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.healthPath = normalizeExactPath(healthPath);
        this.goalStatusBasePath = normalizePrefix(goalStatusBasePath);
        this.endpoints = List.copyOf(endpoints);
        this.annotatedEndpoints = annotatedEndpoints == null ? List.of() : List.copyOf(annotatedEndpoints);
        this.principalExtractor = principalExtractor == null ? DevBearerPrincipalExtractor.INSTANCE : principalExtractor;
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
        routes.add(pathPrefix(PathMatchers.separateOnSlashes(stripSlashes(goalStatusBasePath)), () ->
                path(PathMatchers.segment(), goalId ->
                        get(() -> completeWithFuture(goalStatus(goalId)))
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
        for (EndpointDescriptor endpoint : annotatedEndpoints) {
            routes.add(annotatedRoute(endpoint));
        }
        Route route = routes.getFirst();
        for (int index = 1; index < routes.size(); index++) {
            route = concat(route, routes.get(index));
        }
        return route;
    }

    private Route annotatedRoute(EndpointDescriptor endpoint) {
        return extractRequest(request -> {
            EndpointMatch match = match(endpoint, request);
            if (match == null) {
                return reject();
            }
            return optionalHeaderValueByName("Authorization", authorizationHeader ->
                    completeWithFuture(principalExtractor.authenticateBearer(authorizationHeader)
                            .thenCompose(principal -> authorizeAnnotated(endpoint, match, request, principal)))
            );
        });
    }

    private CompletionStage<HttpResponse> authorizeAnnotated(
            EndpointDescriptor endpoint,
            EndpointMatch match,
            HttpRequest request,
            Optional<Principal> principal
    ) {
        if (principal.isEmpty()) {
            return CompletableFuture.completedFuture(json(StatusCodes.UNAUTHORIZED, "{\"error\":\"authentication_required\"}"));
        }
        if (!principal.get().hasAnyRole(match.method().roles())) {
            return CompletableFuture.completedFuture(json(StatusCodes.FORBIDDEN, "{\"error\":\"forbidden\"}"));
        }
        return invokeAnnotated(endpoint, match, request);
    }

    private CompletionStage<HttpResponse> invokeAnnotated(
            EndpointDescriptor endpoint,
            EndpointMatch match,
            HttpRequest request
    ) {
        CompletionStage<Object[]> arguments = arguments(match, request);
        return arguments.thenCompose(args -> {
            try {
                Object value = match.method().method().invoke(endpoint.instance(), args);
                return result(value).thenApply(body -> json(StatusCodes.OK, toJson(body)));
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                return CompletableFuture.completedFuture(json(StatusCodes.BAD_REQUEST, errorJson(cause)));
            } catch (Exception exception) {
                return CompletableFuture.completedFuture(json(StatusCodes.BAD_REQUEST, errorJson(exception)));
            }
        });
    }

    private CompletionStage<Object[]> arguments(EndpointMatch match, HttpRequest request) {
        Method method = match.method().method();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return CompletableFuture.completedFuture(new Object[0]);
        }
        if (match.method().httpMethod() == HttpMethod.GET || match.method().httpMethod() == HttpMethod.DELETE) {
            Object[] values = new Object[parameterTypes.length];
            List<String> pathValues = new ArrayList<>(match.pathValues().values());
            for (int index = 0; index < parameterTypes.length; index++) {
                values[index] = convert(pathValues.size() > index ? pathValues.get(index) : "", parameterTypes[index]);
            }
            return CompletableFuture.completedFuture(values);
        }
        return request.entity().toStrict(32768, actorSystem)
                .thenApply(strict -> {
                    try {
                        return new Object[]{JSON.readValue(strict.getData().utf8String(), parameterTypes[0])};
                    } catch (Exception exception) {
                        throw new IllegalArgumentException("Invalid request body: " + exception.getMessage(), exception);
                    }
                });
    }

    private static CompletionStage<Object> result(Object value) {
        if (value instanceof CompletionStage<?> stage) {
            return stage.thenApply(result -> result);
        }
        return CompletableFuture.completedFuture(value);
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
                                endpoint.goalFor(request),
                                timeout
                        )
                        .thenApply(result -> json(StatusCodes.OK, toJson(result)));
                case ASYNC -> runtime.componentClient()
                        .forGatewayAgent(endpoint.system(), request.resolvedRequestId())
                        .runSingleGoalAsync(endpoint.goalFor(request), timeout)
                        .thenApply(goalId -> json(
                                StatusCodes.ACCEPTED,
                                toJson(new GoalAccepted(goalId, goalStatusBasePath + goalId))
                        ));
            };
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<HttpResponse> goalStatus(String goalId) {
        return runtime.componentClient().forGoal(goalId).getAsync()
                .thenApply(task -> json(StatusCodes.OK, toJson(task)))
                .exceptionally(error -> json(StatusCodes.BAD_REQUEST, errorJson(error)));
    }

    private static EndpointMatch match(EndpointDescriptor endpoint, HttpRequest request) {
        HttpMethod method = httpMethod(request);
        if (method == null) {
            return null;
        }
        String requestPath = request.getUri().path().toString();
        for (EndpointMethodDescriptor candidate : endpoint.methods()) {
            if (candidate.httpMethod() != method) {
                continue;
            }
            Map<String, String> pathValues = matchPath(endpoint.prefix() + candidate.path(), requestPath);
            if (pathValues != null) {
                return new EndpointMatch(candidate, pathValues);
            }
        }
        return null;
    }

    private static HttpMethod httpMethod(HttpRequest request) {
        if (request.method().equals(HttpMethods.GET)) {
            return HttpMethod.GET;
        }
        if (request.method().equals(HttpMethods.POST)) {
            return HttpMethod.POST;
        }
        if (request.method().equals(HttpMethods.PUT)) {
            return HttpMethod.PUT;
        }
        if (request.method().equals(HttpMethods.PATCH)) {
            return HttpMethod.PATCH;
        }
        if (request.method().equals(HttpMethods.DELETE)) {
            return HttpMethod.DELETE;
        }
        return null;
    }

    private static Map<String, String> matchPath(String template, String path) {
        String[] templateParts = stripSlashes(template).split("/");
        String[] pathParts = stripSlashes(path).split("/");
        if (templateParts.length != pathParts.length) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < templateParts.length; index++) {
            String templatePart = templateParts[index];
            String pathPart = pathParts[index];
            if (templatePart.startsWith("{") && templatePart.endsWith("}")) {
                values.put(templatePart.substring(1, templatePart.length() - 1), pathPart);
            } else if (!templatePart.equals(pathPart)) {
                return null;
            }
        }
        return values;
    }

    private static Object convert(String value, Class<?> type) {
        if (type.equals(String.class)) {
            return value;
        }
        if (type.equals(int.class) || type.equals(Integer.class)) {
            return Integer.parseInt(value);
        }
        if (type.equals(long.class) || type.equals(Long.class)) {
            return Long.parseLong(value);
        }
        if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("Unsupported path parameter type: " + type.getName());
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

    private record GoalAccepted(String goalId, String statusUrl) {
    }

    private record EndpointMatch(EndpointMethodDescriptor method, Map<String, String> pathValues) {
    }

    public static final class Builder {
        private AgentRuntime runtime;
        private String host = "0.0.0.0";
        private int port = 8080;
        private String healthPath = "/health";
        private String goalStatusBasePath = "/v1/agents/goals";
        private final List<AgentHttpEndpoint> endpoints = new ArrayList<>();
        private final List<EndpointDescriptor> annotatedEndpoints = new ArrayList<>();
        private PrincipalExtractor principalExtractor = DevBearerPrincipalExtractor.INSTANCE;
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

        public Builder goalStatusBasePath(String goalStatusBasePath) {
            this.goalStatusBasePath = Objects.requireNonNull(goalStatusBasePath);
            return this;
        }

        public Builder endpoint(AgentHttpEndpoint endpoint) {
            this.endpoints.add(Objects.requireNonNull(endpoint));
            return this;
        }

        public Builder endpoint(AgentEndpoint endpoint) {
            return endpoint(AgentHttpEndpoint.from(endpoint));
        }

        public Builder endpoint(Object endpoint) {
            this.annotatedEndpoints.add(EndpointScanner.scan(endpoint));
            return this;
        }

        public Builder principalExtractor(PrincipalExtractor principalExtractor) {
            this.principalExtractor = Objects.requireNonNull(principalExtractor);
            return this;
        }

        public Builder syncEndpoint(String path, com.example.agent.api.AgentWorkflow workflow) {
            return endpoint(AgentEndpoint.sync(path, workflow));
        }

        public Builder asyncEndpoint(String path, com.example.agent.api.AgentWorkflow workflow) {
            return endpoint(AgentEndpoint.async(path, workflow));
        }

        public Builder syncEndpoint(String path, com.example.agent.api.AgentSystem system, String goalType) {
            return endpoint(AgentHttpEndpoint.sync(path, system, goalType));
        }

        public Builder asyncEndpoint(String path, com.example.agent.api.AgentSystem system, String goalType) {
            return endpoint(AgentHttpEndpoint.async(path, system, goalType));
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
                    goalStatusBasePath,
                    endpoints,
                    annotatedEndpoints,
                    principalExtractor,
                    resolvedSystem,
                    ownsActorSystem
            );
        }
    }
}
