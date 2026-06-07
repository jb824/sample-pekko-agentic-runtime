package com.example.agent.api;

import com.example.agent.config.AppConfig;
import com.example.agent.http.AgentHttpServer;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.consumer.AgentConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class AgentAppRunner {
    private final AgentWorkflowRegistry registry;
    private final AgentConsumerRegistry consumerRegistry;
    private final AppConfig config;

    public AgentAppRunner(AgentWorkflow workflow, AppConfig config) {
        this(new AgentWorkflowRegistry(List.of(Objects.requireNonNull(workflow))), config);
    }

    public AgentAppRunner(AgentWorkflowRegistry registry, AppConfig config) {
        this(registry, AgentConsumerRegistry.discover(config), config);
    }

    public AgentAppRunner(AgentWorkflowRegistry registry, AgentConsumerRegistry consumerRegistry, AppConfig config) {
        this.registry = Objects.requireNonNull(registry);
        this.consumerRegistry = Objects.requireNonNull(consumerRegistry);
        this.config = Objects.requireNonNull(config);
    }

    public static void run(String[] args) {
        new AgentAppRunner(AgentWorkflowRegistry.discover(), AppConfig.fromEnvironment()).runApp(args);
    }

    public void runApp(String[] args) {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_HTTP", "false"))) {
            runHttp();
            return;
        }
        runCli(args == null ? new String[0] : args);
    }

    private void runCli(String[] args) {
        String prompt = args.length == 0
                ? System.getenv().getOrDefault("AGENT_PROMPT", config.testPrompt())
                : String.join(" ", args);
        AgentRunContext context = AgentRunContext.tenant(System.getenv().getOrDefault("AGENT_TENANT_ID", "default"));
        AgentWorkflow workflow = cliWorkflow();

        try (AgentRuntimeClient client = runtimeClientBuilder(List.of(workflow)).build()) {
            AgentResult result = client.run(context, workflow, prompt).toCompletableFuture().join();
            if (result.isSuccess()) {
                System.out.printf("\n\n---\n%s\n---\n\n", result.output());
                return;
            }
            String errorText = result.errors().isEmpty()
                    ? result.status().name()
                    : result.errors().stream()
                    .map(AgentError::message)
                    .reduce((left, right) -> left + "; " + right)
                    .orElse(result.status().name());
            System.err.println("Agent run failed: " + errorText);
            System.exit(1);
        }
    }

    private void runHttp() {
        int port = Integer.parseInt(System.getenv().getOrDefault("AGENT_HTTP_PORT", "8080"));
        List<AgentWorkflow> workflows = registry.workflows();
        if (workflows.isEmpty()) {
            throw new IllegalStateException("No AgentWorkflow implementation found. Register one with @AgentComponent.");
        }
        AgentRuntime runtime = runtimeBuilder(workflows).build();
        AgentHttpServer.Builder serverBuilder = AgentHttpServer.builder()
                .runtime(runtime)
                .port(port);
        boolean multiWorkflow = workflows.size() > 1;
        for (AgentWorkflow workflow : workflows) {
            serverBuilder.endpoint(multiWorkflow ? routedEndpoint(workflow) : workflow.endpoint());
        }
        AgentHttpServer server = serverBuilder.build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            runtime.close();
        }));
        server.start().toCompletableFuture().join();
        System.out.println("Agent HTTP server listening on port " + port);
        awaitForever();
    }

    private AgentRuntimeClient.Builder runtimeClientBuilder(List<AgentWorkflow> workflows) {
        AgentRuntimeClient.Builder builder = AgentRuntimeClient.builder().config(config);
        consumers(workflows).forEach(builder::consumer);
        return builder;
    }

    private AgentRuntime.Builder runtimeBuilder(List<AgentWorkflow> workflows) {
        AgentRuntime.Builder builder = AgentRuntime.builder().config(config);
        consumers(workflows).forEach(builder::consumer);
        return builder;
    }

    private List<AgentConsumer> consumers(List<AgentWorkflow> workflows) {
        return workflows.stream()
                .flatMap(workflow -> {
                    List<AgentConsumer> explicitConsumers = workflow.consumers(config);
                    List<AgentConsumer> discoveredConsumers = consumerRegistry.consumersFor(workflow.workflowId());
                    return java.util.stream.Stream.concat(
                            explicitConsumers == null ? List.<AgentConsumer>of().stream() : explicitConsumers.stream(),
                            discoveredConsumers.stream()
                    );
                })
                .toList();
    }

    private AgentWorkflow cliWorkflow() {
        String configuredId = System.getenv("AGENT_WORKFLOW_ID");
        if (configuredId != null && !configuredId.isBlank()) {
            return registry.workflow(configuredId);
        }
        return registry.single();
    }

    private static AgentEndpoint routedEndpoint(AgentWorkflow workflow) {
        return AgentEndpoint.async("/v1/agents/" + workflow.workflowId(), workflow);
    }

    private static void awaitForever() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public Duration timeout() {
        return registry.single().timeout();
    }
}
