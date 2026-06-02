package com.example.agent.runtime;

import com.example.agent.gateway.GatewayActor;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.runtime.telemetry.Telemetry;
import com.example.agent.workflow.WorkflowSpec;
import io.opentelemetry.api.trace.Span;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public final class ActorAgentRuntimeService implements WorkflowRuntimeService {
    private final ActorRef<GatewayActor.Command> gateway;
    private final Scheduler scheduler;

    public ActorAgentRuntimeService(ActorRef<GatewayActor.Command> gateway, Scheduler scheduler) {
        this.gateway = gateway;
        this.scheduler = scheduler;
    }

    @Override
    public CompletionStage<AgentResult> invoke(AgentRequest request, Duration timeout) {
        return invokeInternal(request, null, null, timeout);
    }

    @Override
    public CompletionStage<AgentResult> invoke(AgentRequest request, WorkflowSpec workflowSpec, Duration timeout) {
        return invokeInternal(request, workflowSpec, null, timeout);
    }

    @Override
    public CompletionStage<AgentResult> invoke(AgentRequest request, AgentSystemDefinition agentSystem, Duration timeout) {
        return invokeInternal(request, null, agentSystem, timeout);
    }

    private CompletionStage<AgentResult> invokeInternal(
            AgentRequest request,
            WorkflowSpec workflowSpec,
            AgentSystemDefinition agentSystem,
            Duration timeout
    ) {
        Span span = Telemetry.startInternalSpan("runtime.invoke");
        span.setAttribute("agent.request_id", request.requestId());
        span.setAttribute("agent.timeout_ms", timeout.toMillis());
        return AskPattern.<GatewayActor.Command, AgentResult>ask(
                gateway,
                replyTo -> {
                    if (agentSystem != null) {
                        return new GatewayActor.HandleAgentSystemRuntimeRequest(request, agentSystem, replyTo);
                    }
                    if (workflowSpec != null) {
                        return new GatewayActor.HandleWorkflowRuntimeRequest(request, workflowSpec, replyTo);
                    }
                    return new GatewayActor.HandleRuntimeRequest(request, replyTo);
                },
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
