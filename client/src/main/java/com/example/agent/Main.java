package com.example.agent;

import com.example.agent.api.AgentRuntimeClient;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.config.AppConfig;
import com.example.agent.http.AgentHttpServer;
import com.example.agent.runtime.AgentError;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        ThreatVulnerabilityTrackerWorkflow workflow = new ThreatVulnerabilityTrackerWorkflow();
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AGENT_HTTP", "false"))) {
            runHttp(config, workflow);
            return;
        }
        EvalConsumer evalConsumer = evalEnabled()
                ? EvalConsumer.fromConfig(config)
                : null;
//        runCli(config, args, workflow, evalConsumer);
        runHttp(config, workflow);
    }

    private static void runCli(
            AppConfig config,
            String[] args,
            AssistantWorkflow workflow,
            EvalConsumer evalConsumer
    ) {
        String prompt = args.length == 0
                ? System.getenv().getOrDefault("AGENT_PROMPT", "What time is it?")
                : String.join(" ", args);
        AgentRunContext context = AgentRunContext.tenant(System.getenv().getOrDefault("AGENT_TENANT_ID", "default"));

        AgentRuntimeClient.Builder builder = AgentRuntimeClient.builder().config(config);
        if (evalConsumer != null) {
            builder.consumer(evalConsumer);
        }

        try (AgentRuntimeClient client = builder.build()) {
            var result = client.run(context, workflow, prompt).toCompletableFuture().join();
            if (result.isSuccess()) {
                System.out.printf("\n\n---\n%s\n---\n\n", result.output());
                if (evalConsumer != null) {
                    evalConsumer.awaitReview(result.requestId(), evalTimeout())
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

    private static void runHttp(AppConfig config, ThreatVulnerabilityTrackerWorkflow workflow) {
        int port = Integer.parseInt(System.getenv().getOrDefault("AGENT_HTTP_PORT", "8080"));
        AgentRuntime.Builder runtimeBuilder = AgentRuntime.builder().config(config);
        if (evalEnabled()) {
            runtimeBuilder.consumer(EvalConsumer.fromConfig(config));
        }
        AgentRuntime runtime = runtimeBuilder.build();
        AgentHttpServer server = AgentHttpServer.builder()
                .runtime(runtime)
                .port(port)
                .endpoint(new ThreatVulnerabilityTrackerEndpoint(runtime, workflow))
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

    private static boolean evalEnabled() {
        String explicitEval = System.getenv("AGENT_EVAL_ENABLED");
        if (explicitEval != null && !explicitEval.isBlank()) {
            return Boolean.parseBoolean(explicitEval);
        }
        String legacyReview = System.getenv("AGENT_REVIEW_ENABLED");
        if (legacyReview != null && !legacyReview.isBlank()) {
            return Boolean.parseBoolean(legacyReview);
        }
        return true;
    }

    private static Duration evalTimeout() {
        String timeout = System.getenv().getOrDefault(
                "AGENT_EVAL_TIMEOUT_SECONDS",
                System.getenv().getOrDefault("AGENT_REVIEW_TIMEOUT_SECONDS", "60")
        );
        return Duration.ofSeconds(Long.parseLong(timeout));
    }
}
