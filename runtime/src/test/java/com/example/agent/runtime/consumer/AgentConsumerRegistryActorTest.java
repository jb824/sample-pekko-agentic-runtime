package com.example.agent.runtime.consumer;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentConsumerRegistryActorTest {
    private final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "consumer-registry-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void dispatchReachesAllRegisteredConsumers() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        ActorRef<AgentConsumerRegistryActor.Command> registry = system.systemActorOf(
                AgentConsumerRegistryActor.create(List.of(
                        new CountingConsumer("a", delivered),
                        new CountingConsumer("b", delivered)
                ), Duration.ofSeconds(1), 10),
                "registry-delivers",
                Props.empty()
        );

        registry.tell(new AgentConsumerRegistryActor.Dispatch(event("request-1")));

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
    }

    @Test
    void unregisterStopsDeliveryToConsumer() throws Exception {
        CountDownLatch firstDelivered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ActorRef<AgentConsumerRegistryActor.Command> registry = system.systemActorOf(
                AgentConsumerRegistryActor.create(List.of(new CountingConsumer("a", firstDelivered, calls)), Duration.ofSeconds(1), 10),
                "registry-unregisters",
                Props.empty()
        );

        registry.tell(new AgentConsumerRegistryActor.Dispatch(event("request-1")));
        assertTrue(firstDelivered.await(2, TimeUnit.SECONDS));
        registry.tell(new AgentConsumerRegistryActor.Unregister("a"));
        registry.tell(new AgentConsumerRegistryActor.Dispatch(event("request-2")));

        Thread.sleep(200L);
        assertEquals(1, calls.get());
    }

    @Test
    void duplicateRegistrationReplacesPreviousWorker() throws Exception {
        CountDownLatch replacementDelivered = new CountDownLatch(1);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        ActorRef<AgentConsumerRegistryActor.Command> registry = system.systemActorOf(
                AgentConsumerRegistryActor.create(List.of(new CountingConsumer("a", new CountDownLatch(1), oldCalls)), Duration.ofSeconds(1), 10),
                "registry-replaces",
                Props.empty()
        );

        registry.tell(new AgentConsumerRegistryActor.Register(new CountingConsumer("a", replacementDelivered, newCalls)));
        registry.tell(new AgentConsumerRegistryActor.Dispatch(event("request-1")));

        assertTrue(replacementDelivered.await(2, TimeUnit.SECONDS));
        assertFalse(oldCalls.get() > 0);
        assertEquals(1, newCalls.get());
    }

    private static AgentCompletedEvent event(String requestId) {
        return new AgentCompletedEvent(
                requestId,
                "tenant-a",
                "gateway",
                "input",
                "output",
                com.example.agent.protocol.AgentStatus.COMPLETED,
                List.of(),
                10L,
                Instant.now()
        );
    }

    private static final class CountingConsumer extends AgentConsumer {
        private final String consumerId;
        private final CountDownLatch latch;
        private final AtomicInteger calls;

        private CountingConsumer(String consumerId, CountDownLatch latch) {
            this(consumerId, latch, new AtomicInteger());
        }

        private CountingConsumer(String consumerId, CountDownLatch latch, AtomicInteger calls) {
            this.consumerId = consumerId;
            this.latch = latch;
            this.calls = calls;
        }

        @Override
        public String consumerId() {
            return consumerId;
        }

        @Override
        public ConsumerEffect onAgentCompleted(AgentCompletedEvent event) {
            calls.incrementAndGet();
            latch.countDown();
            return ConsumerEffect.done();
        }
    }
}
