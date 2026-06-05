package com.example.agent.runtime.consumer;

import org.apache.pekko.actor.Cancellable;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class ConsumerWorkerActor extends AbstractBehavior<ConsumerWorkerActor.Command> {
    private final AgentConsumer consumer;
    private final Executor consumerExecutor;
    private final Duration defaultProcessingTimeout;
    private final int maxQueuedEvents;
    private final Queue<AgentCompletedEvent> pending = new ArrayDeque<>();
    private InFlight inFlight;

    public static Behavior<Command> create(
            AgentConsumer consumer,
            Executor consumerExecutor,
            Duration defaultProcessingTimeout,
            int maxQueuedEvents
    ) {
        return Behaviors.setup(context -> new ConsumerWorkerActor(
                context,
                consumer,
                consumerExecutor,
                defaultProcessingTimeout,
                maxQueuedEvents
        ));
    }

    private ConsumerWorkerActor(
            ActorContext<Command> context,
            AgentConsumer consumer,
            Executor consumerExecutor,
            Duration defaultProcessingTimeout,
            int maxQueuedEvents
    ) {
        super(context);
        this.consumer = Objects.requireNonNull(consumer);
        this.consumerExecutor = Objects.requireNonNull(consumerExecutor);
        this.defaultProcessingTimeout = defaultProcessingTimeout == null
                ? AgentConsumer.DEFAULT_PROCESSING_TIMEOUT
                : defaultProcessingTimeout;
        this.maxQueuedEvents = Math.max(0, maxQueuedEvents);
    }

    public sealed interface Command permits Process, WrappedResult, ProcessingTimeout {
    }

    public record Process(AgentCompletedEvent event) implements Command {
    }

    private record WrappedResult(String requestId, ConsumerEffect effect, Throwable error) implements Command {
    }

    private record ProcessingTimeout(String requestId) implements Command {
    }

    private record InFlight(String requestId, Cancellable timeout, boolean timedOut) {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Process.class, this::onProcess)
                .onMessage(WrappedResult.class, this::onWrappedResult)
                .onMessage(ProcessingTimeout.class, this::onProcessingTimeout)
                .build();
    }

    private Behavior<Command> onProcess(Process process) {
        if (inFlight != null) {
            if (pending.size() >= maxQueuedEvents) {
                getContext().getLog().warn(
                        "Agent consumer queue full; dropping event consumer_id={} request_id={} max_queued={}",
                        consumer.consumerId(), process.event().requestId(), maxQueuedEvents
                );
                return this;
            }
            pending.add(process.event());
            return this;
        }
        dispatch(process.event());
        return this;
    }

    private void dispatch(AgentCompletedEvent event) {
        Duration timeout = processingTimeout();
        Cancellable cancellable = getContext().scheduleOnce(
                timeout,
                getContext().getSelf(),
                new ProcessingTimeout(event.requestId())
        );
        inFlight = new InFlight(event.requestId(), cancellable, false);

        CompletableFuture<ConsumerEffect> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> consumer.onAgentCompleted(event),
                    consumerExecutor
            );
        } catch (RejectedExecutionException exception) {
            inFlight.timeout().cancel();
            getContext().getLog().warn(
                    "Agent consumer executor rejected invocation consumer_id={} request_id={} error={}",
                    consumer.consumerId(), event.requestId(), exception.toString()
            );
            inFlight = null;
            drain();
            return;
        }
        getContext().pipeToSelf(future, (effect, failure) -> new WrappedResult(event.requestId(), effect, failure));
    }

    private Behavior<Command> onWrappedResult(WrappedResult result) {
        if (inFlight == null || !inFlight.requestId().equals(result.requestId())) {
            return this;
        }
        inFlight.timeout().cancel();
        boolean timedOut = inFlight.timedOut();
        if (timedOut) {
            getContext().getLog().warn(
                    "Agent consumer invocation completed after timeout consumer_id={} request_id={}",
                    consumer.consumerId(), result.requestId()
            );
        } else if (result.error() != null) {
            getContext().getLog().warn(
                    "Agent consumer failed consumer_id={} request_id={} error={}",
                    consumer.consumerId(), result.requestId(), result.error().toString()
            );
        } else {
            ConsumerEffect effect = result.effect() == null ? ConsumerEffect.done() : result.effect();
            if (effect.isDone()) {
                getContext().getLog().debug(
                        "Agent consumer completed consumer_id={} request_id={}",
                        consumer.consumerId(), result.requestId()
                );
            } else {
                getContext().getLog().warn(
                        "Agent consumer returned failure consumer_id={} request_id={} reason={}",
                        consumer.consumerId(), result.requestId(), effect.failureReason().orElse("consumer failed")
                );
            }
        }
        inFlight = null;
        drain();
        return this;
    }

    private Behavior<Command> onProcessingTimeout(ProcessingTimeout timeout) {
        if (inFlight == null || !inFlight.requestId().equals(timeout.requestId())) {
            return this;
        }
        getContext().getLog().warn(
                "Agent consumer timed out consumer_id={} request_id={} timeout_ms={}",
                consumer.consumerId(), timeout.requestId(), processingTimeout().toMillis()
        );
        inFlight = new InFlight(inFlight.requestId(), inFlight.timeout(), true);
        return this;
    }

    private void drain() {
        if (inFlight == null && !pending.isEmpty()) {
            dispatch(pending.remove());
        }
    }

    private Duration processingTimeout() {
        return resolveProcessingTimeout(consumer, defaultProcessingTimeout);
    }

    static Duration resolveProcessingTimeout(AgentConsumer consumer, Duration defaultProcessingTimeout) {
        Duration consumerTimeout = consumer.processingTimeout();
        return consumerTimeout == null ? defaultProcessingTimeout : consumerTimeout;
    }
}
