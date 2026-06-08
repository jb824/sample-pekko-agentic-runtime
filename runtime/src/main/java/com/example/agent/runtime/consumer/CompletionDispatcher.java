package com.example.agent.runtime.consumer;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.Objects;

public final class CompletionDispatcher {
    private final ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry;

    public CompletionDispatcher(ActorRef<AgentConsumerRegistryActor.Command> consumerRegistry) {
        this.consumerRegistry = consumerRegistry;
    }

    public void dispatch(AgentCompletedEvent event) {
        if (consumerRegistry == null) {
            return;
        }
        consumerRegistry.tell(new AgentConsumerRegistryActor.Dispatch(Objects.requireNonNull(event)));
    }
}
