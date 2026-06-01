package com.example.agent.gateway;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import com.example.agent.tool.ToolProtocol;
import com.example.agent.tool.ToolCatalog;
import com.example.agent.workflow.PlannerExecutorWorkflowActor;
import com.example.agent.workflow.ReActWorkflowActor;
import com.example.agent.workflow.ResearchWorkflowActor;
import com.example.agent.workflow.WorkflowEngine;
import com.example.agent.workflow.WorkflowSpec;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;

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

    public sealed interface Command permits HandleRequest {
    }

    public record HandleRequest(
            AgentRequest request,
            ActorRef<AgentResponse> replyTo
    ) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleRequest.class, this::onHandleRequest)
                .build();
    }

    private Behavior<Command> onHandleRequest(HandleRequest command) {
        switch (workflowSpec.engine()) {
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
                                String.join(",", workflowSpec.defaultTools()),
                                workflowSpec.maxTools(),
                                workflowSpec.workflowTimeout(),
                                workflowSpec.toolTimeout()
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
                                String.join(",", workflowSpec.defaultTools()),
                                workflowSpec.maxTools(),
                                workflowSpec.maxSteps(),
                                workflowSpec.workflowTimeout(),
                                workflowSpec.toolTimeout(),
                                workflowSpec.maxToolRetries()
                        ),
                        "react-workflow-" + command.request().requestId()
                );
                workflow.tell(new ReActWorkflowActor.Start(command.request(), command.replyTo()));
            }
        }
        return this;
    }
}
