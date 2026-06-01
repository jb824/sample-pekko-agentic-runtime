package com.example.agent;

import com.example.agent.adapter.grpc.GrpcServerAdapter;
import com.example.agent.adapter.http.PekkoHttpAdapter;
import com.example.agent.config.AppConfig;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.llm.LlmWorkerActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.ActorAgentRuntimeService;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.telemetry.TelemetryBootstrap;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.tool.ToolRegistryActor;
import com.example.agent.workflow.WorkflowCatalog;
import com.example.agent.workflow.WorkflowSpec;
import dev.langchain4j.model.chat.ChatModel;
import io.grpc.Server;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        TelemetryBootstrap.initialize();
        AppConfig config = AppConfig.fromEnvironment();
        ChatModel model = ChatModelFactory.create(config);
        ExecutorService llmExecutor = new ThreadPoolExecutor(
                config.llmThreads(),
                config.llmThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.llmQueueSize()),
                namedThreadFactory("llm-worker")
        );

        String runMode = System.getenv().getOrDefault("AGENT_RUN_MODE", "cli").trim().toLowerCase();
        Behavior<AgentResult> root = Behaviors.setup(context -> {
            ActorRef<LlmProtocol.Command> llmWorker = context.spawn(
                    LlmWorkerActor.create(model, llmExecutor),
                    "llm-worker"
            );
            ActorRef<ToolProtocol.Command> toolRegistry = context.spawn(
                    ToolRegistryActor.create(),
                    "tool-registry"
            );
            WorkflowSpec workflowSpec = WorkflowCatalog.fromConfig(config).resolve(config.workflowMode());
            context.getLog().info(
                    "Loaded runtime config from {} (file-loaded={} env-overrides-active={})",
                    config.configPath(),
                    config.configFileLoaded(),
                    config.hasPromptEnvOverrides()
            );
            if (config.hasPromptEnvOverrides()) {
                context.getLog().warn(
                        "Prompt-related environment overrides detected (AGENT_PROMPT/AGENT_PROMPTS/AGENT_PROMPTS_FILE); these take precedence over YAML prompt settings."
                );
            }
            ActorRef<GatewayActor.Command> gateway = context.spawn(
                    GatewayActor.create(
                            llmWorker,
                            toolRegistry,
                            workflowSpec
                    ),
                    "gateway"
            );

            Map<String, AgentRequest> requests = new HashMap<>();
            Map<String, Long> startTimes = new HashMap<>();
            long runStarted = System.nanoTime();
            String requestGroupId = UUID.randomUUID().toString();
            ActorAgentRuntimeService runtimeService = new ActorAgentRuntimeService(gateway, context.getSystem().scheduler());
            if ("server".equals(runMode)) {
                String httpHost = System.getenv().getOrDefault("AGENT_HTTP_HOST", "0.0.0.0");
                int httpPort = Integer.parseInt(System.getenv().getOrDefault("AGENT_HTTP_PORT", "8080"));
                int grpcPort = Integer.parseInt(System.getenv().getOrDefault("AGENT_GRPC_PORT", "8081"));
                boolean enableHttp = Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_ENABLE_HTTP", "true"));
                boolean enableGrpc = Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_ENABLE_GRPC", "true"));

                if (enableHttp) {
                    PekkoHttpAdapter.start(context.getSystem(), runtimeService, httpHost, httpPort)
                            .whenComplete((binding, failure) -> {
                                if (failure != null) {
                                    context.getLog().error("Failed to start Pekko HTTP adapter", failure);
                                    context.getSystem().terminate();
                                } else {
                                    context.getLog().info("Pekko HTTP adapter listening on {}:{}", httpHost, httpPort);
                                }
                            });
                }

                if (enableGrpc) {
                    try {
                        Server grpcServer = GrpcServerAdapter.start(grpcPort, runtimeService);
                        context.getLog().info("gRPC adapter listening on {}", grpcPort);
                        context.getSystem().getWhenTerminated().whenComplete((done, err) -> grpcServer.shutdown());
                    } catch (Exception exception) {
                        context.getLog().error("Failed to start gRPC adapter", exception);
                        context.getSystem().terminate();
                    }
                }

                return Behaviors.receive(AgentResult.class)
                        .onMessage(AgentResult.class, ignored -> Behaviors.same())
                        .build();
            }

            for (int i = 1; i <= config.requestCount(); i++) {
                String requestId = config.requestCount() == 1 ? requestGroupId : requestGroupId + "-" + i;
                AgentRequest request = new AgentRequest(requestId, config.promptForRequest(i));
                requests.put(requestId, request);
                startTimes.put(requestId, System.nanoTime());
                context.getLog().info(
                        "Submitting request {} using {} workflow on {} backend",
                        requestId,
                        workflowSpec.name(),
                        config.llmBackend()
                );
                runtimeService.invoke(request, config.workflowTimeout())
                        .whenComplete((result, failure) -> {
                            if (failure != null) {
                                context.getSelf().tell(new AgentResult(
                                        requestId,
                                        com.example.agent.runtime.AgentStatus.FAILED_SYSTEM,
                                        "",
                                        java.util.List.of(),
                                        java.util.List.of(new AgentError("runtime_invoke_failure", failure.getMessage(), true, "runtime"))
                                ));
                            } else {
                                context.getSelf().tell(result);
                            }
                        });
            }

            AtomicInteger remaining = new AtomicInteger(config.requestCount());
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            return Behaviors.receive(AgentResult.class)
                    .onMessage(AgentResult.class, response -> {
                        printResponse(requests.get(response.requestId()), response);
                        long latencyMs = TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startTimes.getOrDefault(response.requestId(), runStarted)
                        );
                        if (response.isSuccess()) {
                            successes.incrementAndGet();
                        } else {
                            failures.incrementAndGet();
                        }
                        System.out.println("METRIC request_id=" + response.requestId()
                                + " success=" + response.isSuccess()
                                + " latency_ms=" + latencyMs);
                        if (remaining.decrementAndGet() == 0) {
                            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runStarted);
                            System.out.println("METRIC summary requests=" + config.requestCount()
                                    + " success=" + successes.get()
                                    + " failure=" + failures.get()
                                    + " total_ms=" + totalMs
                                    + " workflow=" + config.workflowMode()
                                    + " backend=" + config.llmBackend()
                                    + " tools=" + config.enabledTools());
                            context.getSystem().terminate();
                            return Behaviors.stopped();
                        }
                        return Behaviors.same();
                    })
                    .build();
        });

        ActorSystem<AgentResult> system = ActorSystem.create(root, "pekko-llm-agent-runtime");
        system.getWhenTerminated().toCompletableFuture().join();
        shutdownExecutor(llmExecutor);
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        };
    }

    private static void printResponse(AgentRequest request, AgentResult response) {
        String input = request == null ? "<unknown request>" : request.input();
        System.out.println("Request " + response.requestId() + ": " + input);
        if (response.isSuccess()) {
            System.out.println("Answer: " + response.output());
        } else {
            String errorText = response.errors().isEmpty()
                    ? "<no error>"
                    : response.errors().stream().map(AgentError::message).collect(java.util.stream.Collectors.joining("; "));
            System.err.println("Request failed: " + response.status() + ": " + errorText);
        }
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
