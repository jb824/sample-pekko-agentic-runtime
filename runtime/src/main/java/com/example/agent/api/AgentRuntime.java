package com.example.agent.api;

import com.example.agent.config.AppConfig;
import com.example.agent.config.PekkoRuntimeConfig;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.llm.LlmWorkerActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.rag.ingest.LocalCorpusAutoIngestor;
import com.example.agent.rag.runtime.RagRuntimeActor;
import com.example.agent.rag.runtime.RagRuntimeComponents;
import com.example.agent.rag.runtime.RagRuntimeFactory;
import com.example.agent.runtime.ActorAgentRuntimeService;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.consumer.AgentConsumer;
import com.example.agent.runtime.consumer.AgentCompletedEvent;
import com.example.agent.runtime.consumer.AgentConsumerRegistryActor;
import com.example.agent.runtime.memory.AgentMemoryStore;
import com.example.agent.runtime.memory.AgentMemoryRegistryActor;
import com.example.agent.runtime.memory.InMemoryAgentMemoryStore;
import com.example.agent.runtime.goal.GoalRegistryActor;
import com.example.agent.runtime.agent.PromptBudget;
import com.example.agent.runtime.telemetry.TelemetryBootstrap;
import com.example.agent.runtime.tool.ToolRegistryActor;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentRuntime implements AutoCloseable {
    private final ActorSystem<GatewayActor.Command> actorSystem;
    private final ActorAgentRuntimeService runtimeService;
    private final ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry;
    private final Duration defaultTimeout;
    private final AgentComponentClient componentClient;

    private AgentRuntime(
            ActorSystem<GatewayActor.Command> actorSystem,
            ActorAgentRuntimeService runtimeService,
            ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry,
            Duration defaultTimeout,
            AgentComponentClient componentClient
    ) {
        this.actorSystem = actorSystem;
        this.runtimeService = runtimeService;
        this.consumerRegistry = consumerRegistry;
        this.defaultTimeout = defaultTimeout;
        this.componentClient = componentClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<AgentResult> run(AgentSystem system, GoalRequest goal) {
        return run(AgentRunContext.defaults(), UUID.randomUUID().toString(), system, goal, defaultTimeout);
    }

    public CompletionStage<AgentResult> run(String requestId, AgentSystem system, GoalRequest goal, Duration timeout) {
        return run(AgentRunContext.defaults(), requestId, system, goal, timeout);
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, AgentSystem system, GoalRequest goal) {
        return run(context, UUID.randomUUID().toString(), system, goal, defaultTimeout);
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, String requestId, AgentSystem system, GoalRequest goal, Duration timeout) {
        AgentRunContext resolvedContext = context == null ? AgentRunContext.defaults() : context;
        AgentResult validationFailure = validateGoal(requestId, system, goal);
        if (validationFailure != null) {
            return dispatchCompletedAcknowledged(
                    requestId,
                    resolvedContext.tenantId(),
                    system,
                    goal,
                    validationFailure,
                    0L
            ).thenApply(ignored -> validationFailure);
        }
        return runtimeService.invoke(
                new AgentRequest(requestId, goal.instructions(), resolvedContext.tenantId()),
                system,
                timeout
        );
    }

    private static AgentResult validateGoal(String requestId, AgentSystem system, GoalRequest goal) {
        GoalDefinition definition = system.goalDefinition(goal.name());
        if (definition == null) {
            return new AgentResult(
                    requestId,
                    com.example.agent.runtime.AgentStatus.FAILED_SYSTEM,
                    "",
                    java.util.List.of(),
                    java.util.List.of(new com.example.agent.runtime.AgentError(
                            "unsupported_task",
                            "Goal is not accepted by this agent system: " + goal.name(),
                            false,
                            "goal"
                    ))
            );
        }
        GoalRuleResult validation = definition.validate(goal);
        if (!validation.valid()) {
            return new AgentResult(
                    requestId,
                    com.example.agent.runtime.AgentStatus.FAILED_SYSTEM,
                    "",
                    java.util.List.of(),
                    java.util.List.of(new com.example.agent.runtime.AgentError(
                            "invalid_task",
                            validation.message(),
                            false,
                            "goal"
                    ))
            );
        }
        return null;
    }

    public AgentComponentClient componentClient() {
        return componentClient;
    }

    private void dispatchCompleted(
            String requestId,
            String tenantId,
            AgentSystem system,
            GoalRequest goal,
            AgentResult result,
            long latencyMs
    ) {
        if (consumerRegistry == null) {
            return;
        }
        consumerRegistry.tell(new AgentConsumerRegistryActor.Dispatch(new AgentCompletedEvent(
                requestId,
                tenantId,
                system.entrypoint().name(),
                goal.instructions(),
                result.output(),
                result.status(),
                result.sources(),
                latencyMs,
                Instant.now()
        )));
    }

    private CompletionStage<AgentConsumerRegistryActor.DispatchAccepted> dispatchCompletedAcknowledged(
            String requestId,
            String tenantId,
            AgentSystem system,
            GoalRequest goal,
            AgentResult result,
            long latencyMs
    ) {
        if (consumerRegistry == null) {
            return CompletableFuture.completedFuture(new AgentConsumerRegistryActor.DispatchAccepted(requestId, 0));
        }
        AgentCompletedEvent event = new AgentCompletedEvent(
                requestId,
                tenantId,
                system.entrypoint().name(),
                goal.instructions(),
                result.output(),
                result.status(),
                result.sources(),
                latencyMs,
                Instant.now()
        );
        return AskPattern.<AgentConsumerRegistryActor.Command, AgentConsumerRegistryActor.DispatchAccepted>ask(
                consumerRegistry,
                replyTo -> new AgentConsumerRegistryActor.Dispatch(event, replyTo),
                defaultTimeout,
                actorSystem.scheduler()
        ).exceptionally(failure -> new AgentConsumerRegistryActor.DispatchAccepted(requestId, 0));
    }

    @Override
    public void close() {
        actorSystem.terminate();
        actorSystem.getWhenTerminated().toCompletableFuture().join();
    }

    public static final class Builder {
        private AppConfig config = AppConfig.fromEnvironment();
        private ChatModel chatModel;
        private AgentMemoryStore memoryStore = new InMemoryAgentMemoryStore();
        private RagRuntimeComponents ragRuntimeComponents;
        private final List<AgentToolDefinition> tools = new ArrayList<>();
        private final List<AgentConsumer> consumers = new ArrayList<>();
        private Boolean ragEnabledOverride;
        private boolean telemetryEnabled = true;

        public Builder config(AppConfig config) {
            this.config = Objects.requireNonNull(config);
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = Objects.requireNonNull(chatModel);
            return this;
        }

        public Builder memoryStore(AgentMemoryStore memoryStore) {
            this.memoryStore = Objects.requireNonNull(memoryStore);
            return this;
        }

        public Builder tool(AgentToolDefinition tool) {
            this.tools.add(Objects.requireNonNull(tool));
            return this;
        }

        public Builder tools(AgentToolDefinition... tools) {
            if (tools != null) {
                this.tools.addAll(Arrays.asList(tools));
            }
            return this;
        }

        public Builder tools(List<AgentToolDefinition> tools) {
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public Builder toolsFrom(Object source, Object... otherSources) {
            this.tools.addAll(FunctionTools.from(source, otherSources));
            return this;
        }

        public Builder consumer(AgentConsumer consumer) {
            this.consumers.add(Objects.requireNonNull(consumer));
            return this;
        }

        public Builder ragRuntimeComponents(RagRuntimeComponents ragRuntimeComponents) {
            this.ragRuntimeComponents = Objects.requireNonNull(ragRuntimeComponents);
            return this;
        }

        public Builder ragEnabled(boolean ragEnabled) {
            this.ragEnabledOverride = ragEnabled;
            return this;
        }

        public Builder telemetryEnabled(boolean telemetryEnabled) {
            this.telemetryEnabled = telemetryEnabled;
            return this;
        }

        public AgentRuntime build() {
            if (telemetryEnabled) {
                TelemetryBootstrap.initialize();
            }

            ChatModel resolvedModel = chatModel == null ? ChatModelFactory.create(config) : chatModel;
            RagRuntimeComponents resolvedRag = ragRuntimeComponents == null ? RagRuntimeFactory.create(config) : ragRuntimeComponents;
            boolean ragEnabled = ragEnabledOverride == null ? config.ragEnabled() : ragEnabledOverride;
            PromptBudget promptBudget = new PromptBudget(
                    config.llmContextWindowTokens(),
                    config.vllmMaxTokens(),
                    config.llmContextSafetyTokens()
            );
            List<AgentToolDefinition> resolvedTools = List.copyOf(tools);
            List<AgentConsumer> resolvedConsumers = List.copyOf(consumers);
            java.util.concurrent.CompletableFuture<ActorRef<AgentConsumerRegistryActor.Command>> consumerRegistryRef =
                    new java.util.concurrent.CompletableFuture<>();

            ActorSystem<GatewayActor.Command> system = ActorSystem.create(
                    Behaviors.setup(context -> {
                        var llmWorker = context.spawn(
                                LlmWorkerActor.create(resolvedModel, config.llmThreads(), config.llmQueueSize()),
                                "llm-worker"
                        );
                        var toolRegistry = context.spawn(
                                ToolRegistryActor.create(resolvedTools, config.toolThreads(), config.toolQueueSize()),
                                "tool-registry"
                        );
                        var memoryRegistry = context.spawn(AgentMemoryRegistryActor.create(memoryStore), "agent-memory-registry");
                        var ragRuntime = context.spawn(
                                RagRuntimeActor.create(resolvedRag.retriever(), resolvedRag.indexer()),
                                "rag-runtime"
                        );
                        ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry = resolvedConsumers.isEmpty()
                                ? null
                                : context.spawn(
                                AgentConsumerRegistryActor.create(
                                        resolvedConsumers,
                                        config.consumerProcessingTimeout(),
                                        config.consumerQueueSize()
                                ),
                                "agent-consumer-registry"
                        );
                        consumerRegistryRef.complete(consumerRegistry);
                        LocalCorpusAutoIngestor.maybeIngest(context.getLog(), config);
                        return GatewayActor.create(
                                llmWorker,
                                toolRegistry,
                                memoryRegistry,
                                ragRuntime,
                                consumerRegistry,
                                ragEnabled,
                                config.ragTopK(),
                                config.ragMaxContextChars(),
                                config.workflowTimeout(),
                                config.toolTimeout(),
                                promptBudget,
                                config.maxConcurrentRequests()
                        );
                    }),
                    "pekko-llm-agent-runtime",
                    PekkoRuntimeConfig.forAppConfig(config)
            );
            ActorAgentRuntimeService runtimeService = new ActorAgentRuntimeService(system, system.scheduler());
            ActorRef<GoalRegistryActor.Command> taskRegistry = system.systemActorOf(
                    GoalRegistryActor.create(runtimeService, config.taskRetention(), config.maxRetainedTasks()),
                    "agent-task-registry",
                    Props.empty()
            );

            return new AgentRuntime(
                    system,
                    runtimeService,
                    resolvedConsumers.isEmpty() ? null : consumerRegistryRef.join(),
                    config.workflowTimeout(),
                    new AgentComponentClient(taskRegistry, system.scheduler(), config.workflowTimeout())
            );
        }
    }
}
