package com.example.agent.adapter.grpc;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentRuntimeService;
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
    private final AgentRuntimeService runtimeService;

    public AgentRuntimeGrpcService(AgentRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public void invoke(InvokeRequest request, StreamObserver<InvokeResponse> responseObserver) {
        Span span = Telemetry.startServerSpan("grpc.invoke");
        Duration timeout = request.getTimeoutMs() > 0 ? Duration.ofMillis(request.getTimeoutMs()) : Duration.ofSeconds(60);
        runtimeService.invoke(new AgentRequest(request.getRequestId(), request.getInput()), timeout)
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
