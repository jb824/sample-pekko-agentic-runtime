package com.example.agent.api;

import com.example.agent.config.AppConfig;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.llm.LlmWorkerActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.rag.ingest.LocalCorpusAutoIngestor;
import com.example.agent.runtime.ActorAgentRuntimeService;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.task.AgentTaskRegistryActor;
import com.example.agent.runtime.telemetry.TelemetryBootstrap;
import com.example.agent.tool.ToolRegistryActor;
import com.example.agent.workflow.WorkflowCatalog;
import com.example.agent.workflow.WorkflowSpec;
import com.example.agent.workflow.WorkflowTemplate;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AgentRuntime implements AutoCloseable {
    private final ActorSystem<GatewayActor.Command> actorSystem;
    private final ActorAgentRuntimeService runtimeService;
    private final Duration defaultTimeout;
    private final ExecutorService llmExecutor;
    private final boolean ownsExecutor;
    private final AgentComponentClient componentClient;

    private AgentRuntime(
            ActorSystem<GatewayActor.Command> actorSystem,
            ActorAgentRuntimeService runtimeService,
            Duration defaultTimeout,
            ExecutorService llmExecutor,
            boolean ownsExecutor,
            AgentComponentClient componentClient
    ) {
        this.actorSystem = actorSystem;
        this.runtimeService = runtimeService;
        this.defaultTimeout = defaultTimeout;
        this.llmExecutor = llmExecutor;
        this.ownsExecutor = ownsExecutor;
        this.componentClient = componentClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionStage<AgentResult> run(String input) {
        return run(UUID.randomUUID().toString(), input, defaultTimeout);
    }

    public CompletionStage<AgentResult> run(String requestId, String input, Duration timeout) {
        return runtimeService.invoke(new AgentRequest(requestId, input), timeout);
    }

    public AgentComponentClient componentClient() {
        return componentClient;
    }

    @Override
    public void close() {
        actorSystem.terminate();
        actorSystem.getWhenTerminated().toCompletableFuture().join();
        if (ownsExecutor) {
            llmExecutor.shutdown();
            try {
                if (!llmExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    llmExecutor.shutdownNow();
                }
            } catch (InterruptedException interruptedException) {
                llmExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static final class Builder {
        private AppConfig config = AppConfig.fromEnvironment();
        private WorkflowTemplate workflowTemplate;
        private ChatModel chatModel;
        private ExecutorService llmExecutor;
        private boolean telemetryEnabled = true;

        public Builder config(AppConfig config) {
            this.config = Objects.requireNonNull(config);
            return this;
        }

        public Builder workflow(WorkflowTemplate workflowTemplate) {
            this.workflowTemplate = Objects.requireNonNull(workflowTemplate);
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = Objects.requireNonNull(chatModel);
            return this;
        }

        public Builder llmExecutor(ExecutorService llmExecutor) {
            this.llmExecutor = Objects.requireNonNull(llmExecutor);
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
            boolean ownsExecutor = llmExecutor == null;
            ExecutorService resolvedExecutor = llmExecutor == null
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : llmExecutor;

            WorkflowSpec workflowSpec = workflowTemplate == null
                    ? WorkflowCatalog.fromConfig(config).resolve(config.workflowMode())
                    : workflowTemplate.toSpec(config);

            ActorSystem<GatewayActor.Command> system = ActorSystem.create(
                    Behaviors.setup(context -> {
                        var llmWorker = context.spawn(LlmWorkerActor.create(resolvedModel, resolvedExecutor), "llm-worker");
                        var toolRegistry = context.spawn(ToolRegistryActor.create(config), "tool-registry");
                        LocalCorpusAutoIngestor.maybeIngest(context.getLog(), config);
                        return GatewayActor.create(llmWorker, toolRegistry, workflowSpec);
                    }),
                    "pekko-llm-agent-runtime"
            );
            ActorAgentRuntimeService runtimeService = new ActorAgentRuntimeService(system, system.scheduler());
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry = system.systemActorOf(
                    AgentTaskRegistryActor.create(runtimeService),
                    "agent-task-registry",
                    Props.empty()
            );

            return new AgentRuntime(
                    system,
                    runtimeService,
                    config.workflowTimeout(),
                    resolvedExecutor,
                    ownsExecutor,
                    new AgentComponentClient(taskRegistry, system.scheduler(), config.workflowTimeout())
            );
        }
    }
}
