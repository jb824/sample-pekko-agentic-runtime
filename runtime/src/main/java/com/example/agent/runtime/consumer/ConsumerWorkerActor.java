package com.example.agent.runtime.consumer;

import org.apache.pekko.actor.Cancellable;
import org.apache.pekko.actor.typed.ActorRef;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

public final class ConsumerWorkerActor extends AbstractBehavior<ConsumerWorkerActor.Command> {
    private final AgentConsumer consumer;
    private final Executor consumerExecutor;
    private final Duration defaultProcessingTimeout;
    private final int maxQueuedEvents;
    private final Queue<Pending> pending = new ArrayDeque<>();
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

    public record Process(AgentCompletedEvent event, ActorRef<ProcessCompleted> replyTo, long dispatchId) implements Command {
        public Process(AgentCompletedEvent event) {
            this(event, null, 0L);
        }
    }

    public record ProcessCompleted(long dispatchId, String requestId) {
    }

    private record WrappedResult(long dispatchId, String requestId, ConsumerEffect effect, Throwable error) implements Command {
    }

    private record ProcessingTimeout(long dispatchId, String requestId) implements Command {
    }

    private record Pending(AgentCompletedEvent event, ActorRef<ProcessCompleted> replyTo, long dispatchId) {
    }

    private record InFlight(String requestId, Cancellable timeout, FutureTask<ConsumerEffect> task, ActorRef<ProcessCompleted> replyTo, long dispatchId) {
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
                completeProcess(process.replyTo(), process.dispatchId(), process.event().requestId());
                return this;
            }
            pending.add(new Pending(process.event(), process.replyTo(), process.dispatchId()));
            return this;
        }
        dispatch(new Pending(process.event(), process.replyTo(), process.dispatchId()));
        return this;
    }

    private void dispatch(Pending pendingEvent) {
        AgentCompletedEvent event = pendingEvent.event();
        Duration timeout = processingTimeout();
        Cancellable cancellable = getContext().scheduleOnce(
                timeout,
                getContext().getSelf(),
                new ProcessingTimeout(pendingEvent.dispatchId(), event.requestId())
        );
        CompletableFuture<ConsumerEffect> future = new CompletableFuture<>();
        FutureTask<ConsumerEffect> task = new FutureTask<>(() -> consumer.onAgentCompleted(event)) {
            @Override
            protected void done() {
                if (isCancelled()) {
                    future.complete(ConsumerEffect.fail("consumer invocation cancelled"));
                    return;
                }
                try {
                    future.complete(get());
                } catch (Exception exception) {
                    future.completeExceptionally(exception);
                }
            }
        };
        inFlight = new InFlight(event.requestId(), cancellable, task, pendingEvent.replyTo(), pendingEvent.dispatchId());

        try {
            consumerExecutor.execute(task);
        } catch (RejectedExecutionException exception) {
            inFlight.timeout().cancel();
            getContext().getLog().warn(
                    "Agent consumer executor rejected invocation consumer_id={} request_id={} error={}",
                    consumer.consumerId(), event.requestId(), exception.toString()
            );
            inFlight = null;
            completeProcess(pendingEvent.replyTo(), pendingEvent.dispatchId(), event.requestId());
            drain();
            return;
        }
        getContext().pipeToSelf(future, (effect, failure) -> new WrappedResult(pendingEvent.dispatchId(), event.requestId(), effect, failure));
    }

    private Behavior<Command> onWrappedResult(WrappedResult result) {
        if (inFlight == null || inFlight.dispatchId() != result.dispatchId() || !inFlight.requestId().equals(result.requestId())) {
            return this;
        }
        inFlight.timeout().cancel();
        if (result.error() != null) {
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
        completeProcess(inFlight.replyTo(), inFlight.dispatchId(), inFlight.requestId());
        inFlight = null;
        drain();
        return this;
    }

    private Behavior<Command> onProcessingTimeout(ProcessingTimeout timeout) {
        if (inFlight == null || inFlight.dispatchId() != timeout.dispatchId() || !inFlight.requestId().equals(timeout.requestId())) {
            return this;
        }
        getContext().getLog().warn(
                "Agent consumer timed out consumer_id={} request_id={} timeout_ms={}",
                consumer.consumerId(), timeout.requestId(), processingTimeout().toMillis()
        );
        inFlight.task().cancel(true);
        completeProcess(inFlight.replyTo(), inFlight.dispatchId(), inFlight.requestId());
        inFlight = null;
        drain();
        return this;
    }

    private void drain() {
        if (inFlight == null && !pending.isEmpty()) {
            dispatch(pending.remove());
        }
    }

    private static void completeProcess(ActorRef<ProcessCompleted> replyTo, long dispatchId, String requestId) {
        if (replyTo != null) {
            replyTo.tell(new ProcessCompleted(dispatchId, requestId));
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
