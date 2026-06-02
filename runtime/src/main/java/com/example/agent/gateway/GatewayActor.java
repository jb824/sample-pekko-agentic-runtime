package com.example.agent.gateway;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import com.example.agent.runtime.AgentError;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.AgentSessionActor;
import com.example.agent.runtime.AgentStatus;
import com.example.agent.runtime.agent.AgentSystemActor;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.workflow.PlannerExecutorWorkflowActor;
import com.example.agent.workflow.ResearchWorkflowActor;
import com.example.agent.workflow.WorkflowSpec;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

public final class GatewayActor extends AbstractBehavior<GatewayActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final WorkflowSpec workflowSpec;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            WorkflowSpec workflowSpec
    ) {
        return Behaviors.setup(context -> new GatewayActor(
                context,
                llmWorker,
                toolRegistry,
                workflowSpec
        ));
    }

    private GatewayActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            WorkflowSpec workflowSpec
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.workflowSpec = Objects.requireNonNull(workflowSpec);
    }

    public sealed interface Command permits HandleRequest, HandleRuntimeRequest, HandleWorkflowRuntimeRequest, HandleAgentSystemRuntimeRequest, WrappedLegacyResponse, WrappedRuntimeResult {
    }

    public record HandleRequest(
            AgentRequest request,
            ActorRef<AgentResponse> replyTo
    ) implements Command {
    }

    public record HandleRuntimeRequest(
            AgentRequest request,
            ActorRef<AgentResult> replyTo
    ) implements Command {
    }

    public record HandleWorkflowRuntimeRequest(
            AgentRequest request,
            WorkflowSpec workflowSpec,
            ActorRef<AgentResult> replyTo
    ) implements Command {
    }

    public record HandleAgentSystemRuntimeRequest(
            AgentRequest request,
            AgentSystemDefinition agentSystem,
            ActorRef<AgentResult> replyTo
    ) implements Command {
    }

    private record WrappedLegacyResponse(
            String requestId,
            ActorRef<AgentResult> replyTo,
            AgentResponse response
    ) implements Command {
    }

    private record WrappedRuntimeResult(
            ActorRef<AgentResponse> replyTo,
            AgentResult result
    ) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleRequest.class, this::onHandleRequest)
                .onMessage(HandleRuntimeRequest.class, this::onHandleRuntimeRequest)
                .onMessage(HandleWorkflowRuntimeRequest.class, this::onHandleWorkflowRuntimeRequest)
                .onMessage(HandleAgentSystemRuntimeRequest.class, this::onHandleAgentSystemRuntimeRequest)
                .onMessage(WrappedLegacyResponse.class, this::onWrappedLegacyResponse)
                .onMessage(WrappedRuntimeResult.class, this::onWrappedRuntimeResult)
                .build();
    }

    private Behavior<Command> onHandleRequest(HandleRequest command) {
        ActorRef<AgentResult> runtimeAdapter = getContext().messageAdapter(
                AgentResult.class,
                result -> new WrappedRuntimeResult(command.replyTo(), result)
        );
        return onHandleRuntimeRequest(new HandleRuntimeRequest(command.request(), runtimeAdapter));
    }

    private Behavior<Command> onHandleRuntimeRequest(HandleRuntimeRequest command) {
        return dispatch(command.request(), workflowSpec, command.replyTo());
    }

    private Behavior<Command> onHandleWorkflowRuntimeRequest(HandleWorkflowRuntimeRequest command) {
        return dispatch(command.request(), command.workflowSpec(), command.replyTo());
    }

    private Behavior<Command> onHandleAgentSystemRuntimeRequest(HandleAgentSystemRuntimeRequest command) {
        ActorRef<AgentSystemActor.Command> agentSystemActor = getContext().spawn(
                AgentSystemActor.create(
                        llmWorker,
                        toolRegistry,
                        workflowSpec.workflowTimeout(),
                        workflowSpec.toolTimeout()
                ),
                "agent-system-" + command.request().requestId()
        );
        agentSystemActor.tell(new AgentSystemActor.Start(command.request(), command.agentSystem(), command.replyTo()));
        return this;
    }

    private Behavior<Command> dispatch(AgentRequest request, WorkflowSpec resolvedWorkflowSpec, ActorRef<AgentResult> replyTo) {
        switch (resolvedWorkflowSpec.engine()) {
            case RESEARCH -> {
                ActorRef<ResearchWorkflowActor.Command> workflow = getContext().spawn(
                        ResearchWorkflowActor.create(llmWorker),
                        "research-workflow-" + request.requestId()
                );
                ActorRef<AgentResponse> responseAdapter = legacyAdapter(request.requestId(), replyTo);
                workflow.tell(new ResearchWorkflowActor.StartResearch(request, responseAdapter));
            }
            case PLANNER_EXECUTOR -> {
                ActorRef<PlannerExecutorWorkflowActor.Command> workflow = getContext().spawn(
                        PlannerExecutorWorkflowActor.create(
                                llmWorker,
                                toolRegistry,
                                String.join(",", resolvedWorkflowSpec.defaultTools()),
                                resolvedWorkflowSpec.maxTools(),
                                resolvedWorkflowSpec.workflowTimeout(),
                                resolvedWorkflowSpec.toolTimeout()
                        ),
                        "planner-executor-workflow-" + request.requestId()
                );
                ActorRef<AgentResponse> responseAdapter = legacyAdapter(request.requestId(), replyTo);
                workflow.tell(new PlannerExecutorWorkflowActor.Start(request, responseAdapter));
            }
            case REACT -> {
                ActorRef<AgentSessionActor.Command> session = getContext().spawn(
                        Behaviors.supervise(
                                AgentSessionActor.create(
                                        llmWorker,
                                        toolRegistry,
                                        resolvedWorkflowSpec.defaultTools(),
                                        resolvedWorkflowSpec.maxTools(),
                                        resolvedWorkflowSpec.maxSteps(),
                                        resolvedWorkflowSpec.workflowTimeout(),
                                        resolvedWorkflowSpec.toolTimeout()
                                )
                        ).onFailure(SupervisorStrategy.stop()),
                        "agent-session-react-" + request.requestId()
                );
                session.tell(new AgentSessionActor.Start(request, replyTo));
            }
        }
        return this;
    }

    private Behavior<Command> onWrappedLegacyResponse(WrappedLegacyResponse wrapped) {
        wrapped.replyTo().tell(toRuntime(wrapped.response()));
        return this;
    }

    private Behavior<Command> onWrappedRuntimeResult(WrappedRuntimeResult wrapped) {
        wrapped.replyTo().tell(toLegacy(wrapped.result()));
        return this;
    }

    private ActorRef<AgentResponse> legacyAdapter(String requestId, ActorRef<AgentResult> replyTo) {
        return getContext().messageAdapter(
                AgentResponse.class,
                response -> new WrappedLegacyResponse(requestId, replyTo, response)
        );
    }

    private static AgentResult toRuntime(AgentResponse response) {
        if (response.isSuccess()) {
            return new AgentResult(response.requestId(), AgentStatus.COMPLETED, response.output(), List.of(), List.of());
        }
        return new AgentResult(
                response.requestId(),
                AgentStatus.FAILED_SYSTEM,
                "",
                List.of(),
                List.of(new AgentError("legacy_workflow_error", response.error().getMessage(), false, "workflow"))
        );
    }

    private static AgentResponse toLegacy(AgentResult result) {
        if (result.isSuccess()) {
            return new AgentResponse(result.requestId(), result.output(), null);
        }
        return new AgentResponse(
                result.requestId(),
                result.output(),
                new IllegalStateException(result.status() + ": "
                        + result.errors().stream().map(AgentError::message).collect(Collectors.joining("; ")))
        );
    }
}
