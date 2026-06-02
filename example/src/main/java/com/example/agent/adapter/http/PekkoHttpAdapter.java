package com.example.agent.adapter.http;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentRuntimeService;
import com.example.agent.runtime.WorkflowRuntimeService;
import com.example.agent.runtime.agent.AgentDefinition;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.runtime.agent.GatewayAgentDefinition;
import com.example.agent.runtime.agent.TaskDefinition;
import com.example.agent.runtime.task.AgentTaskRegistryActor;
import com.example.agent.runtime.task.AgentTaskState;
import com.example.agent.runtime.telemetry.Telemetry;
import com.example.agent.workflow.WorkflowEngine;
import com.example.agent.workflow.WorkflowSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.apache.pekko.japi.function.Function;

public final class PekkoHttpAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PekkoHttpAdapter() {
    }

    public static CompletionStage<ServerBinding> start(
            ActorSystem<?> system,
            AgentRuntimeService runtimeService,
            String host,
            int port
    ) {
        return start(system, Map.of("default", runtimeService), "default", null, host, port);
    }

    public static CompletionStage<ServerBinding> start(
            ActorSystem<?> system,
            Map<String, AgentRuntimeService> workflowRuntimes,
            String defaultWorkflow,
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            String host,
            int port
    ) {
        return Http.get(system)
                .newServerAt(host, port)
                .bind((Function<HttpRequest, CompletionStage<HttpResponse>>) request ->
                        handleRequest(system, workflowRuntimes, defaultWorkflow, taskRegistry, request));
    }

    private static CompletionStage<HttpResponse> handleRequest(
            ActorSystem<?> system,
            Map<String, AgentRuntimeService> workflowRuntimes,
            String defaultWorkflow,
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            HttpRequest request
    ) {
        if (request.method().equals(HttpMethods.GET) && "/health".equals(request.getUri().path().toString())) {
            return completed(json(StatusCodes.OK, "{\"status\":\"UP\"}"));
        }
        if (request.method().equals(HttpMethods.POST) && "/v1/agent/invoke".equals(request.getUri().path().toString())) {
            Span span = Telemetry.startServerSpan("http.invoke");
            AgentRuntimeService runtimeService = workflowRuntimes.get(defaultWorkflow);
            return request.entity().toStrict(4096, system)
                    .thenCompose(strict -> invoke(runtimeService, strict))
                    .thenApply(result -> {
                        span.end();
                        return json(StatusCodes.OK, toJson(result));
                    })
                    .exceptionally(error -> {
                        span.recordException(error);
                        span.end();
                        return json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}");
                    });
        }
        if (request.method().equals(HttpMethods.POST) && request.getUri().path().toString().startsWith("/v1/workflows/")) {
            String path = request.getUri().path().toString();
            String[] segments = path.split("/");
            if (segments.length == 5 && "v1".equals(segments[1]) && "workflows".equals(segments[2])
                    && "invoke".equals(segments[4])) {
                String workflowName = segments[3].trim().toLowerCase();
                AgentRuntimeService runtimeService = workflowRuntimes.get(workflowName);
                if (runtimeService == null) {
                    return completed(json(StatusCodes.NOT_FOUND, "{\"error\":\"unsupported_workflow\"}"));
                }
                Span span = Telemetry.startServerSpan("http.invoke." + workflowName);
                return request.entity().toStrict(4096, system)
                        .thenCompose(strict -> invoke(runtimeService, strict))
                        .thenApply(result -> {
                            span.end();
                            return json(StatusCodes.OK, toJson(result));
                        })
                        .exceptionally(error -> {
                            span.recordException(error);
                            span.end();
                            return json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}");
                        });
            }
        }
        if (request.method().equals(HttpMethods.POST) && "/v1/workflows/execute".equals(request.getUri().path().toString())) {
            AgentRuntimeService runtimeService = workflowRuntimes.get(defaultWorkflow);
            if (!(runtimeService instanceof WorkflowRuntimeService workflowRuntimeService)) {
                return completed(json(StatusCodes.BAD_REQUEST, "{\"error\":\"dynamic_workflow_not_supported\"}"));
            }
            Span span = Telemetry.startServerSpan("http.invoke.dynamic_workflow");
            return request.entity().toStrict(16384, system)
                    .thenCompose(strict -> invokeDynamic(workflowRuntimeService, strict))
                    .thenApply(result -> {
                        span.end();
                        return json(StatusCodes.OK, toJson(result));
                    })
                    .exceptionally(error -> {
                        span.recordException(error);
                        span.end();
                        return json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}");
                    });
        }
        if (request.method().equals(HttpMethods.POST) && "/v1/agents/execute".equals(request.getUri().path().toString())) {
            AgentRuntimeService runtimeService = workflowRuntimes.get(defaultWorkflow);
            if (!(runtimeService instanceof WorkflowRuntimeService workflowRuntimeService)) {
                return completed(json(StatusCodes.BAD_REQUEST, "{\"error\":\"agent_system_not_supported\"}"));
            }
            Span span = Telemetry.startServerSpan("http.invoke.agent_system");
            return request.entity().toStrict(32768, system)
                    .thenCompose(strict -> invokeAgentSystem(workflowRuntimeService, strict))
                    .thenApply(result -> {
                        span.end();
                        return json(StatusCodes.OK, toJson(result));
                    })
                    .exceptionally(error -> {
                        span.recordException(error);
                        span.end();
                        return json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}");
                    });
        }
        if (request.method().equals(HttpMethods.POST) && "/v1/agents/tasks".equals(request.getUri().path().toString())) {
            if (taskRegistry == null) {
                return completed(json(StatusCodes.BAD_REQUEST, "{\"error\":\"task_registry_not_configured\"}"));
            }
            Span span = Telemetry.startServerSpan("http.agent_task.start");
            return request.entity().toStrict(32768, system)
                    .thenCompose(strict -> startAgentTask(system, taskRegistry, strict))
                    .thenApply(task -> {
                        span.end();
                        return json(StatusCodes.ACCEPTED, toJson(task));
                    })
                    .exceptionally(error -> {
                        span.recordException(error);
                        span.end();
                        return json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}");
                    });
        }
        if (request.method().equals(HttpMethods.GET) && request.getUri().path().toString().startsWith("/v1/agents/tasks/")) {
            if (taskRegistry == null) {
                return completed(json(StatusCodes.BAD_REQUEST, "{\"error\":\"task_registry_not_configured\"}"));
            }
            String taskId = request.getUri().path().toString().substring("/v1/agents/tasks/".length()).trim();
            return getAgentTask(system, taskRegistry, taskId)
                    .thenApply(task -> json(StatusCodes.OK, toJson(task)))
                    .exceptionally(error -> json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}"));
        }
        request.discardEntityBytes(system);
        return completed(json(StatusCodes.NOT_FOUND, "{\"error\":\"not_found\"}"));
    }

    private static CompletionStage<AgentResult> invoke(
            AgentRuntimeService runtimeService,
            HttpEntity.Strict strictEntity
    ) {
        try {
            InvokeRequest request = JSON.readValue(strictEntity.getData().utf8String(), InvokeRequest.class);
            Duration timeout = request.timeoutMs() > 0 ? Duration.ofMillis(request.timeoutMs()) : Duration.ofSeconds(60);
            return runtimeService.invoke(new AgentRequest(request.requestId(), request.input()), timeout);
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }

    private static CompletionStage<AgentResult> invokeDynamic(
            WorkflowRuntimeService runtimeService,
            HttpEntity.Strict strictEntity
    ) {
        try {
            DynamicInvokeRequest request = JSON.readValue(strictEntity.getData().utf8String(), DynamicInvokeRequest.class);
            if (request.workflow() == null) {
                throw new IllegalArgumentException("workflow definition is required");
            }
            WorkflowSpec spec = toWorkflowSpec(request.workflow());
            Duration timeout = request.timeoutMs() > 0 ? Duration.ofMillis(request.timeoutMs()) : spec.workflowTimeout();
            AgentRequest agentRequest = new AgentRequest(request.requestId(), request.input());
            return runtimeService.invoke(agentRequest, spec, timeout);
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }

    private static WorkflowSpec toWorkflowSpec(WorkflowDef workflow) {
        WorkflowEngine engine = resolveEngine(workflow.engine(), workflow.style());
        List<String> tools = workflow.tools() == null ? List.of() : workflow.tools();
        int maxTools = workflow.maxTools() == null ? 3 : workflow.maxTools();
        int maxSteps = workflow.maxSteps() == null ? 4 : workflow.maxSteps();
        int maxToolRetries = workflow.maxToolRetries() == null ? 1 : workflow.maxToolRetries();
        Duration workflowTimeout = workflow.workflowTimeoutMs() == null
                ? Duration.ofSeconds(180)
                : Duration.ofMillis(workflow.workflowTimeoutMs());
        Duration toolTimeout = workflow.toolTimeoutMs() == null
                ? Duration.ofSeconds(30)
                : Duration.ofMillis(workflow.toolTimeoutMs());
        String name = workflow.name() == null || workflow.name().isBlank() ? "client-defined" : workflow.name().trim().toLowerCase();
        return WorkflowSpec.named(name, engine)
                .defaultTools(tools)
                .maxTools(maxTools)
                .maxSteps(maxSteps)
                .maxToolRetries(maxToolRetries)
                .workflowTimeout(workflowTimeout)
                .toolTimeout(toolTimeout)
                .build();
    }

    private static CompletionStage<AgentResult> invokeAgentSystem(
            WorkflowRuntimeService runtimeService,
            HttpEntity.Strict strictEntity
    ) {
        try {
            AgentRunRequest request = JSON.readValue(strictEntity.getData().utf8String(), AgentRunRequest.class);
            if (request.system() == null || request.system().entrypoint() == null) {
                throw new IllegalArgumentException("agent system requires an entrypoint gateway agent");
            }
            AgentSystemDefinition agentSystem = toAgentSystem(request.system());
            Duration timeout = request.timeoutMs() > 0 ? Duration.ofMillis(request.timeoutMs()) : Duration.ofSeconds(180);
            AgentRequest agentRequest = new AgentRequest(request.requestId(), request.input());
            return runtimeService.invoke(agentRequest, agentSystem, timeout);
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }

    private static AgentSystemDefinition toAgentSystem(AgentSystemDef system) {
        GatewayAgentDef gateway = system.entrypoint();
        TaskDef task = gateway.acceptedTask() == null ? new TaskDef("java.lang.String", 4) : gateway.acceptedTask();
        GatewayAgentDefinition entrypoint = new GatewayAgentDefinition(
                gateway.name(),
                gateway.instructions(),
                gateway.tools(),
                new TaskDefinition(task.type(), task.maxIterations()),
                gateway.delegates()
        );
        List<AgentDefinition> agents = system.agents() == null ? List.of() : system.agents().stream()
                .map(agent -> new AgentDefinition(agent.name(), agent.instructions(), agent.tools()))
                .toList();
        return new AgentSystemDefinition(entrypoint, agents);
    }

    private static CompletionStage<AgentTaskState> startAgentTask(
            ActorSystem<?> system,
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            HttpEntity.Strict strictEntity
    ) {
        try {
            AgentRunRequest request = JSON.readValue(strictEntity.getData().utf8String(), AgentRunRequest.class);
            if (request.system() == null || request.system().entrypoint() == null) {
                throw new IllegalArgumentException("agent system requires an entrypoint gateway agent");
            }
            String taskId = request.requestId() == null || request.requestId().isBlank()
                    ? UUID.randomUUID().toString()
                    : request.requestId();
            Duration timeout = request.timeoutMs() > 0 ? Duration.ofMillis(request.timeoutMs()) : Duration.ofSeconds(180);
            AgentSystemDefinition agentSystem = toAgentSystem(request.system());
            return AskPattern.ask(
                    taskRegistry,
                    replyTo -> new AgentTaskRegistryActor.StartTask(taskId, request.input(), timeout, agentSystem, replyTo),
                    Duration.ofSeconds(5),
                    system.scheduler()
            );
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }

    private static CompletionStage<AgentTaskState> getAgentTask(
            ActorSystem<?> system,
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            String taskId
    ) {
        return AskPattern.ask(
                taskRegistry,
                replyTo -> new AgentTaskRegistryActor.GetTask(taskId, replyTo),
                Duration.ofSeconds(5),
                system.scheduler()
        );
    }

    private static WorkflowEngine resolveEngine(String engine, String style) {
        if (engine != null && !engine.isBlank()) {
            return WorkflowEngine.valueOf(engine.trim().toUpperCase().replace('-', '_'));
        }
        String normalized = style == null ? "" : style.trim().toLowerCase();
        return switch (normalized) {
            case "direct", "single" -> WorkflowEngine.RESEARCH;
            case "plan_and_execute", "plan-execute", "planner-executor" -> WorkflowEngine.PLANNER_EXECUTOR;
            case "react", "react_loop", "react-loop" -> WorkflowEngine.REACT;
            default -> throw new IllegalArgumentException("unsupported workflow style: " + style);
        };
    }

    private static HttpResponse json(StatusCode status, String body) {
        return HttpResponse.create().withStatus(status).withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, body));
    }

    private static String toJson(AgentResult result) {
        return toJsonObject(result);
    }

    private static String toJson(AgentTaskState task) {
        return toJsonObject(task);
    }

    private static String toJsonObject(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private static CompletionStage<HttpResponse> completed(HttpResponse response) {
        return java.util.concurrent.CompletableFuture.completedFuture(response);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }

    private record InvokeRequest(String requestId, String input, long timeoutMs) {
    }

    private record DynamicInvokeRequest(String requestId, String input, long timeoutMs, WorkflowDef workflow) {
    }

    private record WorkflowDef(
            String name,
            String style,
            String engine,
            List<String> tools,
            Integer maxTools,
            Integer maxSteps,
            Integer maxToolRetries,
            Long workflowTimeoutMs,
            Long toolTimeoutMs
    ) {
    }

    private record AgentRunRequest(String requestId, String input, long timeoutMs, AgentSystemDef system) {
    }

    private record AgentSystemDef(GatewayAgentDef entrypoint, List<AgentDef> agents) {
    }

    private record GatewayAgentDef(
            String name,
            String instructions,
            List<String> tools,
            TaskDef acceptedTask,
            List<String> delegates
    ) {
    }

    private record AgentDef(String name, String instructions, List<String> tools) {
    }

    private record TaskDef(String type, int maxIterations) {
    }
}
