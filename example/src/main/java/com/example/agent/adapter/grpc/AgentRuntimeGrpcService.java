package com.example.agent.adapter.grpc;

import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.runtime.grpc.AgentRuntimeGrpc;
import com.example.agent.runtime.grpc.Error;
import com.example.agent.runtime.grpc.HealthRequest;
import com.example.agent.runtime.grpc.HealthResponse;
import com.example.agent.runtime.grpc.InvokeRequest;
import com.example.agent.runtime.grpc.InvokeResponse;
import com.example.agent.runtime.telemetry.Telemetry;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;

import java.time.Duration;

public final class AgentRuntimeGrpcService extends AgentRuntimeGrpc.AgentRuntimeImplBase {
    private final AgentRuntime runtime;
    private final AgentSystem defaultSystem;

    public AgentRuntimeGrpcService(AgentRuntime runtime, AgentSystem defaultSystem) {
        this.runtime = runtime;
        this.defaultSystem = defaultSystem;
    }

    @Override
    public void invoke(InvokeRequest request, StreamObserver<InvokeResponse> responseObserver) {
        Span span = Telemetry.startServerSpan("grpc.invoke");
        Duration timeout = request.getTimeoutMs() > 0 ? Duration.ofMillis(request.getTimeoutMs()) : Duration.ofSeconds(60);
        runtime.run(
                        request.getRequestId(),
                        defaultSystem,
                        GoalRequest.of(defaultSystem.entrypoint().acceptedGoal().name()).instructions(request.getInput()).build(),
                        timeout
                )
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        span.recordException(failure);
                        span.end();
                        responseObserver.onError(Status.INTERNAL.withDescription(failure.getMessage()).asRuntimeException());
                        return;
                    }
                    responseObserver.onNext(toProto(result));
                    responseObserver.onCompleted();
                    span.end();
                });
    }

    @Override
    public void health(HealthRequest request, StreamObserver<HealthResponse> responseObserver) {
        responseObserver.onNext(HealthResponse.newBuilder().setStatus("UP").build());
        responseObserver.onCompleted();
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
