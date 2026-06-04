package com.example.agent.runtime.memory;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class AgentMemoryRegistryActor extends AbstractBehavior<AgentMemoryRegistryActor.Command> {
    private final AgentMemoryStore store;
    private final Clock clock;

    public static Behavior<Command> create(AgentMemoryStore store) {
        return create(store, Clock.systemUTC());
    }

    public static Behavior<Command> create(AgentMemoryStore store, Clock clock) {
        return Behaviors.setup(context -> new AgentMemoryRegistryActor(context, store, clock));
    }

    private AgentMemoryRegistryActor(ActorContext<Command> context, AgentMemoryStore store, Clock clock) {
        super(context);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public sealed interface Command permits Recall, Append {
    }

    public record Recall(AgentMemoryKey key, int maxEvents, ActorRef<Recalled> replyTo) implements Command {
    }

    public record Append(AgentMemoryKey key, AgentMemoryEventType type, String requestId, String content, int maxEvents) implements Command {
    }

    public record Recalled(AgentMemoryKey key, List<AgentMemoryEvent> events) {
        public Recalled {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Recall.class, this::onRecall)
                .onMessage(Append.class, this::onAppend)
                .build();
    }

    private Behavior<Command> onRecall(Recall recall) {
        recall.replyTo().tell(new Recalled(recall.key(), store.recent(recall.key(), recall.maxEvents())));
        return this;
    }

    private Behavior<Command> onAppend(Append append) {
        store.append(
                append.key(),
                new AgentMemoryEvent(clock.instant(), append.type(), append.requestId(), append.content()),
                append.maxEvents()
        );
        return this;
    }
}
