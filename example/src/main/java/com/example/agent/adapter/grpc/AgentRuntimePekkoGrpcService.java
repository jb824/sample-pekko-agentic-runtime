package com.example.agent.adapter.grpc;

import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.runtime.grpc.AgentRuntime;
import com.example.agent.runtime.grpc.Error;
import com.example.agent.runtime.grpc.HealthRequest;
import com.example.agent.runtime.grpc.HealthResponse;
import com.example.agent.runtime.grpc.InvokeRequest;
import com.example.agent.runtime.grpc.InvokeResponse;
import com.example.agent.runtime.telemetry.Telemetry;
import io.opentelemetry.api.trace.Span;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public final class AgentRuntimePekkoGrpcService implements AgentRuntime {
    private final com.example.agent.api.AgentRuntime runtime;
    private final AgentSystem defaultSystem;

    public AgentRuntimePekkoGrpcService(com.example.agent.api.AgentRuntime runtime, AgentSystem defaultSystem) {
        this.runtime = runtime;
        this.defaultSystem = defaultSystem;
    }

    @Override
    public CompletionStage<InvokeResponse> invoke(InvokeRequest request) {
        Span span = Telemetry.startServerSpan("grpc.invoke");
        Duration timeout = request.getTimeoutMs() > 0 ? Duration.ofMillis(request.getTimeoutMs()) : Duration.ofSeconds(60);
        return runtime.run(
                        request.getRequestId(),
                        defaultSystem,
                        GoalRequest.of(defaultSystem.entrypoint().acceptedGoal().name()).instructions(request.getInput()).build(),
                        timeout
                )
                .thenApply(AgentRuntimePekkoGrpcService::toProto)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        span.recordException(failure);
                    }
                    span.end();
                });
    }

    @Override
    public CompletionStage<HealthResponse> health(HealthRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(
                HealthResponse.newBuilder().setStatus("UP").build()
        );
    }

    private static InvokeResponse toProto(AgentResult result) {
        InvokeResponse.Builder builder = InvokeResponse.newBuilder()
                .setRequestId(result.requestId())
                .setStatus(result.status().name())
                .setOutput(result.output() == null ? "" : result.output())
                .addAllSources(result.sources());
        result.errors().forEach(error -> builder.addErrors(Error.newBuilder()
                .setCode(error.code())
                .setMessage(error.message())
                .setRetryable(error.retryable())
                .setComponent(error.component())
                .build()));
        return builder.build();
    }
}
