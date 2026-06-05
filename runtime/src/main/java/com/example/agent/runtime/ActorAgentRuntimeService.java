package com.example.agent.runtime;

import com.example.agent.api.AgentSystem;
import com.example.agent.gateway.GatewayActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.telemetry.Telemetry;
import io.opentelemetry.api.trace.Span;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public final class ActorAgentRuntimeService implements AgentRuntimeService {
    private final ActorRef<GatewayActor.Command> gateway;
    private final Scheduler scheduler;

    public ActorAgentRuntimeService(ActorRef<GatewayActor.Command> gateway, Scheduler scheduler) {
        this.gateway = gateway;
        this.scheduler = scheduler;
    }

    @Override
    public CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystem agentSystem, Duration timeout) {
        Span span = Telemetry.startInternalSpan("runtime.invoke");
        span.setAttribute("agent.request_id", request.requestId());
        span.setAttribute("agent.timeout_ms", timeout.toMillis());
        return AskPattern.<GatewayActor.Command, AgentResult>ask(
                gateway,
                replyTo -> new GatewayActor.HandleAgentSystemRuntimeRequest(request, agentSystem, replyTo),
                timeout,
                scheduler
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                span.recordException(failure);
            } else if (result != null) {
                span.setAttribute("agent.status", result.status().name());
            }
            span.end();
        });
    }
}
