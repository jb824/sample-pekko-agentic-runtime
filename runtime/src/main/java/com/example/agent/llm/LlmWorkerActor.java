package com.example.agent.llm;

import com.example.agent.config.PekkoRuntimeConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class LlmWorkerActor extends AbstractBehavior<LlmProtocol.Command> {
    private final ChatModel model;
    private final Executor llmExecutor;
    private final int maxConcurrent;
    private final int maxQueued;
    private final Deque<LlmProtocol.Ask> pending = new ArrayDeque<>();
    private int inFlight;

    public static Behavior<LlmProtocol.Command> create(ChatModel model, int maxConcurrent, int maxQueued) {
        return Behaviors.setup(context -> new LlmWorkerActor(
                context,
                model,
                context.getSystem().dispatchers().lookup(DispatcherSelector.fromConfig(PekkoRuntimeConfig.LLM_DISPATCHER_PATH)),
                maxConcurrent,
                maxQueued
        ));
    }

    static Behavior<LlmProtocol.Command> create(ChatModel model, Executor llmExecutor, int maxConcurrent, int maxQueued) {
        return Behaviors.setup(context -> new LlmWorkerActor(
                context,
                model,
                llmExecutor,
                maxConcurrent,
                maxQueued
        ));
    }

    private LlmWorkerActor(
            ActorContext<LlmProtocol.Command> context,
            ChatModel model,
            Executor llmExecutor,
            int maxConcurrent,
            int maxQueued
    ) {
        super(context);
        this.model = Objects.requireNonNull(model);
        this.llmExecutor = Objects.requireNonNull(llmExecutor);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxQueued = Math.max(0, maxQueued);
    }

    @Override
    public Receive<LlmProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(LlmProtocol.Ask.class, this::onAsk)
                .onMessage(LlmProtocol.WrappedResult.class, this::onWrappedResult)
                .build();
    }

    private Behavior<LlmProtocol.Command> onAsk(LlmProtocol.Ask ask) {
        if (inFlight < maxConcurrent) {
            dispatch(ask);
            return this;
        }
        if (pending.size() < maxQueued) {
            pending.addLast(ask);
            return this;
        }
        ask.replyTo().tell(new LlmProtocol.Response(
                ask.requestId(),
                "",
                new RejectedExecutionException(
                        "LLM worker saturated: in_flight=" + inFlight + " queued=" + pending.size()
                                + " max_concurrent=" + maxConcurrent + " max_queued=" + maxQueued
                )
        ));
        return this;
    }

    private void dispatch(LlmProtocol.Ask ask) {
        inFlight++;
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> model.chat(ask.prompt()),
                    llmExecutor
            );
        } catch (RejectedExecutionException exception) {
            inFlight--;
            ask.replyTo().tell(new LlmProtocol.Response(
                    ask.requestId(),
                    "",
                    exception
            ));
            drainQueue();
            return;
        }

        getContext().pipeToSelf(future, (answer, failure) -> new LlmProtocol.WrappedResult(
                ask.requestId(),
                ask.replyTo(),
                answer,
                failure
        ));
    }

    private Behavior<LlmProtocol.Command> onWrappedResult(LlmProtocol.WrappedResult result) {
        inFlight = Math.max(0, inFlight - 1);
        result.replyTo().tell(new LlmProtocol.Response(
                result.requestId(),
                result.text() == null ? "" : result.text(),
                result.error()
        ));
        drainQueue();
        return this;
    }

    private void drainQueue() {
        while (inFlight < maxConcurrent && !pending.isEmpty()) {
            dispatch(pending.removeFirst());
        }
    }
}
