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
import com.example.agent.runtime.memory.AgentMemoryStore;
import com.example.agent.runtime.memory.AgentMemoryRegistryActor;
import com.example.agent.runtime.memory.InMemoryAgentMemoryStore;
import com.example.agent.runtime.task.AgentTaskRegistryActor;
import com.example.agent.runtime.telemetry.TelemetryBootstrap;
import com.example.agent.tool.ToolRegistryActor;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class AgentRuntime implements AutoCloseable {
    private final ActorSystem<GatewayActor.Command> actorSystem;
    private final ActorAgentRuntimeService runtimeService;
    private final Duration defaultTimeout;
    private final AgentComponentClient componentClient;

    private AgentRuntime(
            ActorSystem<GatewayActor.Command> actorSystem,
            ActorAgentRuntimeService runtimeService,
            Duration defaultTimeout,
            AgentComponentClient componentClient
    ) {
        this.actorSystem = actorSystem;
        this.runtimeService = runtimeService;
        this.defaultTimeout = defaultTimeout;
        this.componentClient = componentClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<AgentResult> run(AgentSystem system, AgentTask task) {
        return run(AgentRunContext.defaults(), UUID.randomUUID().toString(), system, task, defaultTimeout);
    }

    public CompletionStage<AgentResult> run(String requestId, AgentSystem system, AgentTask task, Duration timeout) {
        return run(AgentRunContext.defaults(), requestId, system, task, timeout);
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, AgentSystem system, AgentTask task) {
        return run(context, UUID.randomUUID().toString(), system, task, defaultTimeout);
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, String requestId, AgentSystem system, AgentTask task, Duration timeout) {
        AgentRunContext resolvedContext = context == null ? AgentRunContext.defaults() : context;
        return runtimeService.invoke(
                new AgentRequest(requestId, task.instructions(), resolvedContext.tenantId()),
                AgentSystemMapper.toRuntime(system),
                timeout
        );
    }

    public AgentComponentClient componentClient() {
        return componentClient;
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

            ActorSystem<GatewayActor.Command> system = ActorSystem.create(
                    Behaviors.setup(context -> {
                        var llmWorker = context.spawn(
                                LlmWorkerActor.create(resolvedModel, config.llmThreads(), config.llmQueueSize()),
                                "llm-worker"
                        );
                        var toolRegistry = context.spawn(ToolRegistryActor.create(config), "tool-registry");
                        var memoryRegistry = context.spawn(AgentMemoryRegistryActor.create(memoryStore), "agent-memory-registry");
                        var ragRuntime = context.spawn(
                                RagRuntimeActor.create(resolvedRag.retriever(), resolvedRag.indexer()),
                                "rag-runtime"
                        );
                        LocalCorpusAutoIngestor.maybeIngest(context.getLog(), config);
                        return GatewayActor.create(
                                llmWorker,
                                toolRegistry,
                                memoryRegistry,
                                ragRuntime,
                                ragEnabled,
                                config.ragTopK(),
                                config.ragMaxContextChars(),
                                config.workflowTimeout(),
                                config.toolTimeout()
                        );
                    }),
                    "pekko-llm-agent-runtime",
                    PekkoRuntimeConfig.forAppConfig(config)
            );
            ActorAgentRuntimeService runtimeService = new ActorAgentRuntimeService(system, system.scheduler());
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry = system.systemActorOf(
                    AgentTaskRegistryActor.create(runtimeService, config.taskRetention(), config.maxRetainedTasks()),
                    "agent-task-registry",
                    Props.empty()
            );

            return new AgentRuntime(
                    system,
                    runtimeService,
                    config.workflowTimeout(),
                    new AgentComponentClient(taskRegistry, system.scheduler(), config.workflowTimeout())
            );
        }
    }
}
