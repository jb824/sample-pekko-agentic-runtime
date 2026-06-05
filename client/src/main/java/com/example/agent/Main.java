package com.example.agent;

import com.example.agent.api.AgentRuntimeClient;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.config.AppConfig;
import com.example.agent.http.AgentHttpServer;
import com.example.agent.runtime.AgentError;
import com.example.agent.tools.SampleTools;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        AssistantWorkflow workflow = new AssistantWorkflow(
                SampleTools.timeNow(java.time.Clock.systemUTC()),
                SampleTools.webSearch()
        );
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_HTTP", "false"))) {
            runHttp(config, workflow);
            return;
        }
        AssistantResponseReviewConsumer reviewConsumer = reviewEnabled()
                ? AssistantResponseReviewConsumer.fromConfig(config)
                : null;
        runCli(config, args, workflow, reviewConsumer);
    }

    private static void runCli(
            AppConfig config,
            String[] args,
            AssistantWorkflow workflow,
            AssistantResponseReviewConsumer reviewConsumer
    ) {
        String prompt = args.length == 0
                ? System.getenv().getOrDefault("AGENT_PROMPT", "What time is it?")
                : String.join(" ", args);
        AgentRunContext context = AgentRunContext.tenant(System.getenv().getOrDefault("AGENT_TENANT_ID", "default"));

        AgentRuntimeClient.Builder builder = AgentRuntimeClient.builder().config(config);
        if (reviewConsumer != null) {
            builder.consumer(reviewConsumer);
        }

        try (AgentRuntimeClient client = builder.build()) {
            var result = client.run(context, workflow, prompt).toCompletableFuture().join();
            if (result.isSuccess()) {
                System.out.println(result.output());
                if (reviewConsumer != null) {
                    reviewConsumer.awaitReview(result.requestId(), reviewTimeout())
                            .ifPresent(report -> System.out.println(report.format()));
                }
                return;
            }
            String errorText = result.errors().isEmpty()
                    ? result.status().name()
                    : result.errors().stream().map(AgentError::message).reduce((left, right) -> left + "; " + right).orElse(result.status().name());
            System.err.println("Agent run failed: " + errorText);
            System.exit(1);
        }
    }

    private static void runHttp(AppConfig config, AssistantWorkflow workflow) {
        int port = Integer.parseInt(System.getenv().getOrDefault("AGENT_HTTP_PORT", "8080"));
        AgentRuntime.Builder runtimeBuilder = AgentRuntime.builder().config(config);
        if (reviewEnabled()) {
            runtimeBuilder.consumer(AssistantResponseReviewConsumer.fromConfig(config));
        }
        AgentRuntime runtime = runtimeBuilder.build();
        AgentHttpServer server = AgentHttpServer.builder()
                .runtime(runtime)
                .port(port)
                .endpoint(AssistantEndpoint.http(workflow))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            runtime.close();
        }));
        server.start().toCompletableFuture().join();
        System.out.println("Agent HTTP server listening on port " + port);
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean reviewEnabled() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_REVIEW_ENABLED", "false"));
    }

    private static Duration reviewTimeout() {
        return Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("AGENT_REVIEW_TIMEOUT_SECONDS", "60")));
    }
}
