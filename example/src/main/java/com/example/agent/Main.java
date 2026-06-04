package com.example.agent;

import com.example.agent.adapter.grpc.GrpcServerAdapter;
import com.example.agent.api.Agent;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Task;
import com.example.agent.config.AppConfig;
import com.example.agent.config.PekkoRuntimeConfig;
import com.example.agent.http.AgentHttpServer;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.llm.LlmWorkerActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.rag.ingest.LocalCorpusAutoIngestor;
import com.example.agent.rag.runtime.RagProtocol;
import com.example.agent.rag.runtime.RagRuntimeActor;
import com.example.agent.rag.runtime.RagRuntimeComponents;
import com.example.agent.rag.runtime.RagRuntimeFactory;
import com.example.agent.runtime.ActorAgentRuntimeService;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentRuntimeService;
import com.example.agent.runtime.agent.AgentDefinition;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.runtime.agent.GatewayAgentDefinition;
import com.example.agent.runtime.agent.MemoryDefinition;
import com.example.agent.runtime.agent.TaskDefinition;
import com.example.agent.runtime.memory.AgentMemoryRegistryActor;
import com.example.agent.runtime.memory.InMemoryAgentMemoryStore;
import com.example.agent.runtime.task.AgentTaskRegistryActor;
import com.example.agent.runtime.telemetry.TelemetryBootstrap;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.tool.ToolCatalog;
import com.example.agent.tool.ToolRegistryActor;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.http.javadsl.ServerBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        TelemetryBootstrap.initialize();
        AppConfig config = AppConfig.fromEnvironment();
        ChatModel model = ChatModelFactory.create(config);

        String runMode = System.getenv().getOrDefault("AGENT_RUN_MODE", "cli").trim().toLowerCase();
        if ("server".equals(runMode)) {
            runServer(config, model);
            return;
        }

        Behavior<AgentResult> root = Behaviors.setup(context -> {
            ActorRef<LlmProtocol.Command> llmWorker = context.spawn(
                    LlmWorkerActor.create(model, config.llmThreads(), config.llmQueueSize()),
                    "llm-worker"
            );
            ActorRef<ToolProtocol.Command> toolRegistry = context.spawn(
                    ToolRegistryActor.create(config),
                    "tool-registry"
            );
            ActorRef<AgentMemoryRegistryActor.Command> memoryRegistry = context.spawn(
                    AgentMemoryRegistryActor.create(new InMemoryAgentMemoryStore()),
                    "agent-memory-registry"
            );
            RagRuntimeComponents ragComponents = RagRuntimeFactory.create(config);
            ActorRef<RagProtocol.Command> ragRuntime = context.spawn(
                    RagRuntimeActor.create(ragComponents.retriever(), ragComponents.indexer()),
                    "rag-runtime"
            );
            AgentSystemDefinition defaultSystem = defaultAgentSystem(config);
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
            LocalCorpusAutoIngestor.maybeIngest(context.getLog(), config);
            ActorRef<GatewayActor.Command> gateway = context.spawn(
                    GatewayActor.create(
                            llmWorker,
                            toolRegistry,
                            memoryRegistry,
                            ragRuntime,
                            config.ragEnabled(),
                            config.ragTopK(),
                            config.ragMaxContextChars(),
                            config.workflowTimeout(),
                            config.toolTimeout()
                    ),
                    "gateway"
            );

            Map<String, AgentRequest> requests = new HashMap<>();
            Map<String, Long> startTimes = new HashMap<>();
            long runStarted = System.nanoTime();
            String requestGroupId = UUID.randomUUID().toString();
            ActorAgentRuntimeService runtimeService = new ActorAgentRuntimeService(gateway, context.getSystem().scheduler());

            for (int i = 1; i <= config.requestCount(); i++) {
                String requestId = config.requestCount() == 1 ? requestGroupId : requestGroupId + "-" + i;
                AgentRequest request = new AgentRequest(requestId, config.promptForRequest(i));
                requests.put(requestId, request);
                startTimes.put(requestId, System.nanoTime());
                context.getLog().info(
                        "Submitting request {} using embedded agent system on {} backend",
                        requestId,
                        config.llmBackend()
                );
                runtimeService.invoke(request, defaultSystem, config.workflowTimeout())
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
                                    + " backend=" + config.llmBackend()
                                    + " tools=" + config.enabledTools());
                            context.getSystem().terminate();
                            return Behaviors.stopped();
                        }
                        return Behaviors.same();
                    })
                    .build();
        });

        ActorSystem<AgentResult> system = ActorSystem.create(
                root,
                "pekko-llm-agent-runtime",
                PekkoRuntimeConfig.forAppConfig(config)
        );
        system.getWhenTerminated().toCompletableFuture().join();
    }

    private static void runServer(AppConfig config, ChatModel model) {
        String httpHost = System.getenv().getOrDefault("AGENT_HTTP_HOST", "0.0.0.0");
        int httpPort = Integer.parseInt(System.getenv().getOrDefault("AGENT_HTTP_PORT", "8080"));
        String grpcHost = System.getenv().getOrDefault("AGENT_GRPC_HOST", "0.0.0.0");
        int grpcPort = Integer.parseInt(System.getenv().getOrDefault("AGENT_GRPC_PORT", "8081"));
        boolean enableHttp = Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_ENABLE_HTTP", "true"));
        boolean enableGrpc = Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_ENABLE_GRPC", "true"));

        AgentSystem system = defaultApiAgentSystem(config);
        AgentRuntime runtime = AgentRuntime.builder()
                .config(config)
                .chatModel(model)
                .telemetryEnabled(false)
                .build();
        ActorSystem<Void> transportSystem = ActorSystem.create(Behaviors.empty(), "pekko-agent-transport");
        AtomicReference<ServerBinding> grpcBinding = new AtomicReference<>();

        AgentHttpServer httpServer = enableHttp
                ? AgentHttpServer.builder()
                .runtime(runtime)
                .actorSystem(transportSystem)
                .host(httpHost)
                .port(httpPort)
                .syncEndpoint("/v1/agents/execute", system, "agent.request")
                .asyncEndpoint("/v1/agents/tasks", system, "agent.request")
                .build()
                : null;

        transportSystem.getWhenTerminated().whenComplete((done, error) -> {
            if (httpServer != null) {
                httpServer.close();
            }
            ServerBinding binding = grpcBinding.get();
            if (binding != null) {
                binding.terminate(java.time.Duration.ofSeconds(3));
            }
            runtime.close();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> transportSystem.terminate(), "agent-example-server-shutdown"));

        if (httpServer != null) {
            httpServer.start().whenComplete((binding, failure) -> {
                if (failure != null) {
                    System.err.println("Failed to start HTTP server: " + failure.getMessage());
                    transportSystem.terminate();
                } else {
                    System.out.println("HTTP server listening on " + httpHost + ":" + httpPort);
                }
            });
        }

        if (enableGrpc) {
            GrpcServerAdapter.start(transportSystem, grpcHost, grpcPort, runtime, system)
                    .whenComplete((binding, failure) -> {
                        if (failure != null) {
                            System.err.println("Failed to start gRPC adapter: " + failure.getMessage());
                            transportSystem.terminate();
                        } else {
                            grpcBinding.set(binding);
                            System.out.println("gRPC adapter listening on " + grpcHost + ":" + grpcPort);
                        }
                    });
        }

        transportSystem.getWhenTerminated().toCompletableFuture().join();
    }

    private static AgentSystemDefinition defaultAgentSystem(AppConfig config) {
        AgentSystem system = defaultApiAgentSystem(config);
        return new AgentSystemDefinition(
                new GatewayAgentDefinition(
                        system.entrypoint().name(),
                        system.entrypoint().instructions(),
                        system.entrypoint().tools(),
                        memory(
                                system.entrypoint().memory().enabled(),
                                system.entrypoint().memory().maxEvents(),
                                system.entrypoint().memory().rememberUserTasks(),
                                system.entrypoint().memory().rememberToolObservations(),
                                system.entrypoint().memory().rememberAgentOutputs(),
                                system.entrypoint().memory().rememberFinalAnswers(),
                                system.entrypoint().memory().rememberFailures()
                        ),
                        new TaskDefinition(system.entrypoint().acceptedTask().type(), system.entrypoint().acceptedTask().maxIterations()),
                        system.entrypoint().delegates()
                ),
                system.agents().stream()
                        .map(agent -> new AgentDefinition(
                                agent.name(),
                                agent.instructions(),
                                agent.tools(),
                                memory(
                                        agent.memory().enabled(),
                                        agent.memory().maxEvents(),
                                        agent.memory().rememberUserTasks(),
                                        agent.memory().rememberToolObservations(),
                                        agent.memory().rememberAgentOutputs(),
                                        agent.memory().rememberFinalAnswers(),
                                        agent.memory().rememberFailures()
                                )
                        ))
                        .toList()
        );
    }

    private static MemoryDefinition memory(
            boolean enabled,
            int maxEvents,
            boolean rememberUserTasks,
            boolean rememberToolObservations,
            boolean rememberAgentOutputs,
            boolean rememberFinalAnswers,
            boolean rememberFailures
    ) {
        return new MemoryDefinition(
                enabled,
                maxEvents,
                rememberUserTasks,
                rememberToolObservations,
                rememberAgentOutputs,
                rememberFinalAnswers,
                rememberFailures
        );
    }

    private static AgentSystem defaultApiAgentSystem(AppConfig config) {
        String[] tools = ToolCatalog.parseEnabledTools(config.enabledTools()).toArray(String[]::new);
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer the user request directly, use tools when available, and return a concise factual result.")
                .uses(tools)
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(Task.of("agent.request").maxIterations(1).build())
                .delegatesTo(assistant)
                .instructedBy("Delegate the request to the assistant agent and return the final answer.")
                .build();
        AgentSystem system = AgentSystem.builder()
                .entrypoint(gateway)
                .agent(assistant)
                .build();
        return system;
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

}
