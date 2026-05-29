package com.example.agent.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public final class LlmWorkerActor extends AbstractBehavior<LlmProtocol.Command> {
    private final ChatModel model;
    private final ExecutorService llmExecutor;

    public static Behavior<LlmProtocol.Command> create(ChatModel model, ExecutorService llmExecutor) {
        return Behaviors.setup(context -> new LlmWorkerActor(context, model, llmExecutor));
    }

    private LlmWorkerActor(
            ActorContext<LlmProtocol.Command> context,
            ChatModel model,
            ExecutorService llmExecutor
    ) {
        super(context);
        this.model = Objects.requireNonNull(model);
        this.llmExecutor = Objects.requireNonNull(llmExecutor);
    }

    @Override
    public Receive<LlmProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(LlmProtocol.Ask.class, this::onAsk)
                .onMessage(LlmProtocol.WrappedResult.class, this::onWrappedResult)
                .build();
    }

    private Behavior<LlmProtocol.Command> onAsk(LlmProtocol.Ask ask) {
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> model.chat(ask.prompt()),
                    llmExecutor
            );
        } catch (RejectedExecutionException exception) {
            ask.replyTo().tell(new LlmProtocol.Response(
                    ask.requestId(),
                    "",
                    exception
            ));
            return this;
        }

        getContext().pipeToSelf(future, (answer, failure) -> new LlmProtocol.WrappedResult(
                ask.requestId(),
                ask.replyTo(),
                answer,
                failure
        ));

        return this;
    }

    private Behavior<LlmProtocol.Command> onWrappedResult(LlmProtocol.WrappedResult result) {
        result.replyTo().tell(new LlmProtocol.Response(
                result.requestId(),
                result.text() == null ? "" : result.text(),
                result.error()
        ));
        return this;
    }
}
