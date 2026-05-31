package com.example.agent;

import com.example.agent.config.AppConfig;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.kafka.GuardianKafkaIngestionRunner;
import com.example.agent.kafka.KafkaRuntimeRunner;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.llm.LlmWorkerActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.tool.ToolRegistryActor;
import com.example.agent.tool.ToolWiring;
import com.example.agent.tool.DefaultToolWiring;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import dev.langchain4j.model.chat.ChatModel;
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
    private static final String ACTOR_SYSTEM_NAME = "pekko-llm-agent-runtime";

    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        Config runtimeConfig = ConfigFactory.load();
        switch (AppMode.fromValue(config.appMode())) {
            case GUARDIAN_KAFKA_INGEST -> GuardianKafkaIngestionRunner.run(config, runtimeConfig);
            case KAFKA_RUNTIME -> KafkaRuntimeRunner.run(config);
            case AGENT -> runStandaloneAgent(config);
        }
    }

    private static void runStandaloneAgent(AppConfig config) {
        ChatModel model = ChatModelFactory.create(config);
        ExecutorService llmExecutor = createLlmExecutor(config);
        try {
            Behavior<AgentResponse> root = createRootBehavior(config, model, llmExecutor);
            ActorSystem<AgentResponse> system = ActorSystem.create(root, ACTOR_SYSTEM_NAME);
            system.getWhenTerminated().toCompletableFuture().join();
        } finally {
            shutdownExecutor(llmExecutor);
        }
    }

    private static ExecutorService createLlmExecutor(AppConfig config) {
        return new ThreadPoolExecutor(
                config.llmThreads(),
                config.llmThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.llmQueueSize()),
                namedThreadFactory("llm-worker")
        );
    }

    private static Behavior<AgentResponse> createRootBehavior(
            AppConfig config,
            ChatModel model,
            ExecutorService llmExecutor
    ) {
        return Behaviors.setup(context -> {
            ActorRef<LlmProtocol.Command> llmWorker = context.spawn(
                    LlmWorkerActor.create(model, llmExecutor),
                    "llm-worker"
            );
            ActorRef<ToolProtocol.Command> toolRegistry = context.spawn(
                    ToolRegistryActor.create(createToolWiring(config)),
                    "tool-registry"
            );
            GatewayActor.WorkflowKind workflowKind = GatewayActor.WorkflowKind.fromConfig(config.workflowMode());
            ActorRef<GatewayActor.Command> gateway = context.spawn(
                    GatewayActor.create(
                            llmWorker,
                            toolRegistry,
                            workflowKind,
                            config.enabledTools(),
                            config.maxTools(),
                            config.maxSteps(),
                            config.workflowTimeout(),
                            config.toolTimeout()
                    ),
                    "gateway"
            );
            return createRunBehavior(config, workflowKind, gateway);
        });
    }

    private static Behavior<AgentResponse> createRunBehavior(
            AppConfig config,
            GatewayActor.WorkflowKind workflowKind,
            ActorRef<GatewayActor.Command> gateway
    ) {
        return Behaviors.setup(context -> {
            Map<String, AgentRequest> requests = new HashMap<>();
            Map<String, Long> startTimes = new HashMap<>();
            long runStarted = System.nanoTime();
            submitRequests(config, workflowKind, gateway, context.getSelf(), context, requests, startTimes);

            AtomicInteger remaining = new AtomicInteger(config.requestCount());
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            return Behaviors.receive(AgentResponse.class)
                    .onMessage(AgentResponse.class, response -> {
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
                            printSummaryMetrics(config, successes.get(), failures.get(), totalMs);
                            context.getSystem().terminate();
                            return Behaviors.stopped();
                        }
                        return Behaviors.same();
                    })
                    .build();
        });
    }

    private static void submitRequests(
            AppConfig config,
            GatewayActor.WorkflowKind workflowKind,
            ActorRef<GatewayActor.Command> gateway,
            ActorRef<AgentResponse> replyTo,
            org.apache.pekko.actor.typed.javadsl.ActorContext<AgentResponse> context,
            Map<String, AgentRequest> requests,
            Map<String, Long> startTimes
    ) {
        String requestGroupId = UUID.randomUUID().toString();
        for (int i = 1; i <= config.requestCount(); i++) {
            String requestId = config.requestCount() == 1 ? requestGroupId : requestGroupId + "-" + i;
            AgentRequest request = new AgentRequest(requestId, config.promptForRequest(i));
            requests.put(requestId, request);
            startTimes.put(requestId, System.nanoTime());
            context.getLog().info(
                    "Submitting request {} using {} workflow on {} backend",
                    requestId,
                    workflowKind,
                    config.llmBackend()
            );
            gateway.tell(new GatewayActor.HandleRequest(request, replyTo));
        }
    }

    private static void printSummaryMetrics(AppConfig config, int successCount, int failureCount, long totalMs) {
        System.out.println("METRIC summary requests=" + config.requestCount()
                + " success=" + successCount
                + " failure=" + failureCount
                + " total_ms=" + totalMs
                + " workflow=" + config.workflowMode()
                + " backend=" + config.llmBackend()
                + " tools=" + config.enabledTools());
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        };
    }

    private static void printResponse(AgentRequest request, AgentResponse response) {
        String input = request == null ? "<unknown request>" : request.input();
        System.out.println("Request " + response.requestId() + ": " + input);
        if (response.isSuccess()) {
            System.out.println("Answer: " + response.output());
        } else {
            System.err.println("Request failed: " + response.error().getClass().getSimpleName()
                    + ": " + response.error().getMessage());
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

    private static ToolWiring createToolWiring(AppConfig config) {
        return new DefaultToolWiring();
    }

    private enum AppMode {
        GUARDIAN_KAFKA_INGEST,
        KAFKA_RUNTIME,
        AGENT;

        static AppMode fromValue(String value) {
            if ("guardian-kafka-ingest".equalsIgnoreCase(value)) {
                return GUARDIAN_KAFKA_INGEST;
            }
            if ("kafka-runtime".equalsIgnoreCase(value)) {
                return KAFKA_RUNTIME;
            }
            return AGENT;
        }
    }
}
