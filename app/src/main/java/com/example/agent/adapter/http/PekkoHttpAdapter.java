package com.example.agent.adapter.http;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentRuntimeService;
import com.example.agent.runtime.telemetry.Telemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.model.ContentTypes;
import org.apache.pekko.http.javadsl.model.HttpEntities;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpMethods;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCode;
import org.apache.pekko.http.javadsl.model.StatusCodes;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import org.apache.pekko.japi.function.Function;

public final class PekkoHttpAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PekkoHttpAdapter() {
    }

    public static CompletionStage<ServerBinding> start(
            ActorSystem<?> system,
            AgentRuntimeService runtimeService,
            String host,
            int port
    ) {
        return Http.get(system)
                .newServerAt(host, port)
                .bind((Function<HttpRequest, CompletionStage<HttpResponse>>) request -> handleRequest(system, runtimeService, request));
    }

    private static CompletionStage<HttpResponse> handleRequest(
            ActorSystem<?> system,
            AgentRuntimeService runtimeService,
            HttpRequest request
    ) {
        if (request.method().equals(HttpMethods.GET) && "/health".equals(request.getUri().path().toString())) {
            return completed(json(StatusCodes.OK, "{\"status\":\"UP\"}"));
        }
        if (request.method().equals(HttpMethods.POST) && "/v1/agent/invoke".equals(request.getUri().path().toString())) {
            Span span = Telemetry.startServerSpan("http.invoke");
            return request.entity().toStrict(4096, system)
                    .thenCompose(strict -> invoke(runtimeService, strict))
                    .thenApply(result -> {
                        span.end();
                        return json(StatusCodes.OK, toJson(result));
                    })
                    .exceptionally(error -> {
                        span.recordException(error);
                        span.end();
                        return json(StatusCodes.BAD_REQUEST, "{\"error\":\"" + safe(error.getMessage()) + "\"}");
                    });
        }
        request.discardEntityBytes(system);
        return completed(json(StatusCodes.NOT_FOUND, "{\"error\":\"not_found\"}"));
    }

    private static CompletionStage<AgentResult> invoke(
            AgentRuntimeService runtimeService,
            HttpEntity.Strict strictEntity
    ) {
        try {
            InvokeRequest request = JSON.readValue(strictEntity.getData().utf8String(), InvokeRequest.class);
            Duration timeout = request.timeoutMs() > 0 ? Duration.ofMillis(request.timeoutMs()) : Duration.ofSeconds(60);
            return runtimeService.invoke(new AgentRequest(request.requestId(), request.input()), timeout);
        } catch (Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
    }

    private static HttpResponse json(StatusCode status, String body) {
        return HttpResponse.create().withStatus(status).withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, body));
    }

    private static String toJson(AgentResult result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (Exception exception) {
            return "{\"requestId\":\"" + safe(result.requestId()) + "\",\"status\":\"" + result.status() + "\"}";
        }
    }

    private static CompletionStage<HttpResponse> completed(HttpResponse response) {
        return java.util.concurrent.CompletableFuture.completedFuture(response);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }

    private record InvokeRequest(String requestId, String input, long timeoutMs) {
    }
}
