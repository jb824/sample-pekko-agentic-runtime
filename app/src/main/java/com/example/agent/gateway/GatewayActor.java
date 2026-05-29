package com.example.agent.gateway;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.workflow.PlannerExecutorWorkflowActor;
import com.example.agent.workflow.ReActWorkflowActor;
import com.example.agent.workflow.ResearchWorkflowActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Locale;
import java.util.Objects;

public final class GatewayActor extends AbstractBehavior<GatewayActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;
    private final ActorRef<ToolProtocol.Command> toolRegistry;
    private final WorkflowKind workflowKind;
    private final String enabledTools;
    private final int maxTools;
    private final int maxSteps;
    private final java.time.Duration workflowTimeout;
    private final java.time.Duration toolTimeout;

    public static Behavior<Command> create(
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            WorkflowKind workflowKind,
            String enabledTools,
            int maxTools,
            int maxSteps,
            java.time.Duration workflowTimeout,
            java.time.Duration toolTimeout
    ) {
        return Behaviors.setup(context -> new GatewayActor(
                context,
                llmWorker,
                toolRegistry,
                workflowKind,
                enabledTools,
                maxTools,
                maxSteps,
                workflowTimeout,
                toolTimeout
        ));
    }

    private GatewayActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker,
            ActorRef<ToolProtocol.Command> toolRegistry,
            WorkflowKind workflowKind,
            String enabledTools,
            int maxTools,
            int maxSteps,
            java.time.Duration workflowTimeout,
            java.time.Duration toolTimeout
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.workflowKind = Objects.requireNonNull(workflowKind);
        this.enabledTools = Objects.requireNonNull(enabledTools);
        this.maxTools = maxTools;
        this.maxSteps = maxSteps;
        this.workflowTimeout = Objects.requireNonNull(workflowTimeout);
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
    }

    public sealed interface Command permits HandleRequest {
    }

    public record HandleRequest(
            AgentRequest request,
            ActorRef<AgentResponse> replyTo
    ) implements Command {
    }

    public enum WorkflowKind {
        RESEARCH,
        PLANNER_EXECUTOR,
        REACT;

        public static WorkflowKind fromConfig(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
            return switch (normalized) {
                case "research" -> RESEARCH;
                case "planner", "planner-executor", "planner_executor" -> PLANNER_EXECUTOR;
                case "react", "re-act", "re_act" -> REACT;
                default -> throw new IllegalArgumentException("Unsupported workflow mode: " + value);
            };
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleRequest.class, this::onHandleRequest)
                .build();
    }

    private Behavior<Command> onHandleRequest(HandleRequest command) {
        switch (workflowKind) {
            case RESEARCH -> {
                ActorRef<ResearchWorkflowActor.Command> workflow = getContext().spawn(
                        ResearchWorkflowActor.create(llmWorker),
                        "research-workflow-" + command.request().requestId()
                );
                workflow.tell(new ResearchWorkflowActor.StartResearch(command.request(), command.replyTo()));
            }
            case PLANNER_EXECUTOR -> {
                ActorRef<PlannerExecutorWorkflowActor.Command> workflow = getContext().spawn(
                        PlannerExecutorWorkflowActor.create(
                                llmWorker,
                                toolRegistry,
                                enabledTools,
                                maxTools,
                                workflowTimeout,
                                toolTimeout
                        ),
                        "planner-executor-workflow-" + command.request().requestId()
                );
                workflow.tell(new PlannerExecutorWorkflowActor.Start(command.request(), command.replyTo()));
            }
            case REACT -> {
                ActorRef<ReActWorkflowActor.Command> workflow = getContext().spawn(
                        ReActWorkflowActor.create(
                                llmWorker,
                                toolRegistry,
                                enabledTools,
                                maxTools,
                                maxSteps,
                                workflowTimeout,
                                toolTimeout
                        ),
                        "react-workflow-" + command.request().requestId()
                );
                workflow.tell(new ReActWorkflowActor.Start(command.request(), command.replyTo()));
            }
        }
        return this;
    }
}
