package com.example.agent.workflow;

import com.example.agent.llm.LlmProtocol;
import com.example.agent.prompts.PromptTemplates;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResponse;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;

public final class ResearchWorkflowActor extends AbstractBehavior<ResearchWorkflowActor.Command> {
    private final ActorRef<LlmProtocol.Command> llmWorker;

    public static Behavior<Command> create(ActorRef<LlmProtocol.Command> llmWorker) {
        return Behaviors.setup(context -> new ResearchWorkflowActor(context, llmWorker));
    }

    private ResearchWorkflowActor(
            ActorContext<Command> context,
            ActorRef<LlmProtocol.Command> llmWorker
    ) {
        super(context);
        this.llmWorker = Objects.requireNonNull(llmWorker);
    }

    public sealed interface Command permits StartResearch, WrappedLlmResponse {
    }

    public record StartResearch(
            AgentRequest request,
            ActorRef<AgentResponse> replyTo
    ) implements Command {
    }

    private record WrappedLlmResponse(
            LlmProtocol.Response response,
            String requestId,
            ActorRef<AgentResponse> replyTo
    ) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartResearch.class, this::onStartResearch)
                .onMessage(WrappedLlmResponse.class, this::onWrappedLlmResponse)
                .build();
    }

    private Behavior<Command> onStartResearch(StartResearch start) {
        WorkflowLogger.event(
                getContext(),
                "research",
                start.request().requestId(),
                "started",
                "correlation_id=" + start.request().requestId() + ":research"
        );
        ActorRef<LlmProtocol.Response> responseAdapter = getContext().messageAdapter(
                LlmProtocol.Response.class,
                response -> new WrappedLlmResponse(response, start.request().requestId(), start.replyTo())
        );

        String correlationId = start.request().requestId() + ":research";
        WorkflowLogger.event(
                getContext(),
                "research",
                start.request().requestId(),
                "llm_request",
                "correlation_id=" + correlationId
        );
        llmWorker.tell(new LlmProtocol.Ask(
                correlationId,
                PromptTemplates.researchPrompt(start.request().input()),
                responseAdapter
        ));

        return this;
    }

    private Behavior<Command> onWrappedLlmResponse(WrappedLlmResponse wrapped) {
        LlmProtocol.Response llmResponse = wrapped.response();
        WorkflowLogger.event(
                getContext(),
                "research",
                wrapped.requestId(),
                "llm_response",
                "correlation_id=" + llmResponse.requestId() + " success=" + llmResponse.isSuccess()
        );
        wrapped.replyTo().tell(new AgentResponse(
                baseRequestId(llmResponse.requestId()),
                llmResponse.text(),
                llmResponse.error()
        ));
        WorkflowLogger.event(
                getContext(),
                "research",
                wrapped.requestId(),
                "final",
                "success=" + llmResponse.isSuccess()
        );
        return Behaviors.stopped();
    }

    private static String baseRequestId(String correlationId) {
        int separator = correlationId.indexOf(':');
        return separator < 0 ? correlationId : correlationId.substring(0, separator);
    }
}
