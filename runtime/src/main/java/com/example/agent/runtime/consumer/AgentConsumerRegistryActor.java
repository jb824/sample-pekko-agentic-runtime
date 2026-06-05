package com.example.agent.runtime.consumer;

import com.example.agent.config.PekkoRuntimeConfig;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

public final class AgentConsumerRegistryActor extends AbstractBehavior<AgentConsumerRegistryActor.Command> {
    private final Map<String, ActorRef<ConsumerWorkerActor.Command>> workers = new LinkedHashMap<>();
    private final Map<Long, PendingDispatch> pendingDispatches = new LinkedHashMap<>();
    private final Executor consumerExecutor;
    private final Duration defaultProcessingTimeout;
    private final int maxQueuedEvents;
    private long workerSequence;
    private long dispatchSequence;

    public static Behavior<Command> create(
            List<AgentConsumer> consumers,
            Duration defaultProcessingTimeout,
            int maxQueuedEvents
    ) {
        return Behaviors.setup(context -> {
            Executor executor = context.getSystem().dispatchers()
                    .lookup(DispatcherSelector.fromConfig(PekkoRuntimeConfig.CONSUMER_DISPATCHER_PATH));
            AgentConsumerRegistryActor registry = new AgentConsumerRegistryActor(context, executor, defaultProcessingTimeout, maxQueuedEvents);
            for (AgentConsumer consumer : consumers == null ? List.<AgentConsumer>of() : consumers) {
                registry.register(consumer);
            }
            return registry;
        });
    }

    private AgentConsumerRegistryActor(
            ActorContext<Command> context,
            Executor consumerExecutor,
            Duration defaultProcessingTimeout,
            int maxQueuedEvents
    ) {
        super(context);
        this.consumerExecutor = Objects.requireNonNull(consumerExecutor);
        this.defaultProcessingTimeout = defaultProcessingTimeout == null
                ? AgentConsumer.DEFAULT_PROCESSING_TIMEOUT
                : defaultProcessingTimeout;
        this.maxQueuedEvents = Math.max(0, maxQueuedEvents);
    }

    public sealed interface Command permits Register, Unregister, Dispatch, WorkerStopped, WrappedProcessCompleted {
    }

    public record Register(AgentConsumer consumer) implements Command {
    }

    public record Unregister(String consumerId) implements Command {
    }

    public record DispatchAccepted(String requestId, int consumerCount) {
    }

    public record Dispatch(AgentCompletedEvent event, ActorRef<DispatchAccepted> replyTo) implements Command {
        public Dispatch(AgentCompletedEvent event) {
            this(event, null);
        }
    }

    private record WorkerStopped(String consumerId, ActorRef<ConsumerWorkerActor.Command> worker) implements Command {
    }

    private record WrappedProcessCompleted(long dispatchId, String requestId) implements Command {
    }

    private record PendingDispatch(String requestId, ActorRef<DispatchAccepted> replyTo, int remaining, int consumerCount) {
        PendingDispatch decrement() {
            return new PendingDispatch(requestId, replyTo, remaining - 1, consumerCount);
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Register.class, this::onRegister)
                .onMessage(Unregister.class, this::onUnregister)
                .onMessage(Dispatch.class, this::onDispatch)
                .onMessage(WorkerStopped.class, this::onWorkerStopped)
                .onMessage(WrappedProcessCompleted.class, this::onWrappedProcessCompleted)
                .build();
    }

    private Behavior<Command> onRegister(Register register) {
        register(register.consumer());
        return this;
    }

    private Behavior<Command> onUnregister(Unregister unregister) {
        ActorRef<ConsumerWorkerActor.Command> worker = workers.remove(unregister.consumerId());
        if (worker != null) {
            getContext().stop(worker);
        }
        return this;
    }

    private Behavior<Command> onDispatch(Dispatch dispatch) {
        if (dispatch.replyTo() != null) {
            String requestId = dispatch.event() == null ? "" : dispatch.event().requestId();
            if (workers.isEmpty()) {
                dispatch.replyTo().tell(new DispatchAccepted(requestId, 0));
                return this;
            }
            long dispatchId = ++dispatchSequence;
            pendingDispatches.put(dispatchId, new PendingDispatch(requestId, dispatch.replyTo(), workers.size(), workers.size()));
            ActorRef<ConsumerWorkerActor.ProcessCompleted> adapter = getContext().messageAdapter(
                    ConsumerWorkerActor.ProcessCompleted.class,
                    completed -> new WrappedProcessCompleted(completed.dispatchId(), completed.requestId())
            );
            for (ActorRef<ConsumerWorkerActor.Command> worker : workers.values()) {
                worker.tell(new ConsumerWorkerActor.Process(dispatch.event(), adapter, dispatchId));
            }
            return this;
        }
        for (ActorRef<ConsumerWorkerActor.Command> worker : workers.values()) {
            worker.tell(new ConsumerWorkerActor.Process(dispatch.event()));
        }
        return this;
    }

    private Behavior<Command> onWrappedProcessCompleted(WrappedProcessCompleted completed) {
        PendingDispatch pending = pendingDispatches.get(completed.dispatchId());
        if (pending == null) {
            return this;
        }
        PendingDispatch next = pending.decrement();
        if (next.remaining() <= 0) {
            pendingDispatches.remove(completed.dispatchId());
            pending.replyTo().tell(new DispatchAccepted(pending.requestId(), pending.consumerCount()));
        } else {
            pendingDispatches.put(completed.dispatchId(), next);
        }
        return this;
    }

    private Behavior<Command> onWorkerStopped(WorkerStopped stopped) {
        ActorRef<ConsumerWorkerActor.Command> current = workers.get(stopped.consumerId());
        if (current != null && current.equals(stopped.worker())) {
            workers.remove(stopped.consumerId());
            getContext().getLog().warn("Agent consumer worker stopped consumer_id={}", stopped.consumerId());
        }
        return this;
    }

    private void register(AgentConsumer consumer) {
        Objects.requireNonNull(consumer);
        String consumerId = consumer.consumerId();
        if (consumerId == null || consumerId.isBlank()) {
            throw new IllegalArgumentException("consumerId must not be blank");
        }
        ActorRef<ConsumerWorkerActor.Command> existing = workers.remove(consumerId);
        if (existing != null) {
            getContext().stop(existing);
        }
        ActorRef<ConsumerWorkerActor.Command> worker = getContext().spawn(
                ConsumerWorkerActor.create(consumer, consumerExecutor, defaultProcessingTimeout, maxQueuedEvents),
                "consumer-" + actorName(consumerId) + "-" + (++workerSequence)
        );
        workers.put(consumerId, worker);
        getContext().watchWith(worker, new WorkerStopped(consumerId, worker));
        getContext().getLog().info("Agent consumer registered consumer_id={}", consumerId);
    }

    private static String actorName(String consumerId) {
        return consumerId.replaceAll("[^A-Za-z0-9_-]", "-");
    }
}
