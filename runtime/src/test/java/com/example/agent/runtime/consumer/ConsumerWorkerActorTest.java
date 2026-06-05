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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConsumerWorkerActorTest {
    private final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "consumer-worker-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void deliversEventToConsumer() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<AgentCompletedEvent> observed = new AtomicReference<>();
        SpyConsumer consumer = new SpyConsumer("spy", event -> {
            observed.set(event);
            received.countDown();
            return ConsumerEffect.done();
        });

        var executor = Executors.newSingleThreadExecutor();
        try {
            ActorRef<ConsumerWorkerActor.Command> worker = system.systemActorOf(
                    ConsumerWorkerActor.create(consumer, executor, Duration.ofSeconds(1), 10),
                    "worker-delivers",
                    Props.empty()
            );

            worker.tell(new ConsumerWorkerActor.Process(event("request-1")));

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals("request-1", observed.get().requestId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutInterruptsInFlightAndStartsNextEvent() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        CountDownLatch secondReceived = new CountDownLatch(1);
        AtomicReference<String> observed = new AtomicReference<>();
        SpyConsumer consumer = new SpyConsumer("spy", event -> {
            if ("request-1".equals(event.requestId())) {
                firstStarted.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException interrupted) {
                    firstInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return ConsumerEffect.done();
            }
            observed.set(event.requestId());
            secondReceived.countDown();
            return ConsumerEffect.done();
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            ActorRef<ConsumerWorkerActor.Command> worker = system.systemActorOf(
                    ConsumerWorkerActor.create(consumer, executor, Duration.ofMillis(50), 10),
                    "worker-timeout",
                    Props.empty()
            );

            worker.tell(new ConsumerWorkerActor.Process(event("request-1")));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            worker.tell(new ConsumerWorkerActor.Process(event("request-2")));

            assertTrue(firstInterrupted.await(2, TimeUnit.SECONDS));
            assertTrue(secondReceived.await(2, TimeUnit.SECONDS));
            assertEquals("request-2", observed.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void dropsEventsWhenPendingQueueIsFull() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch acceptedSecond = new CountDownLatch(1);
        AtomicReference<String> lastObserved = new AtomicReference<>();
        SpyConsumer consumer = new SpyConsumer("spy", event -> {
            if ("request-1".equals(event.requestId())) {
                firstStarted.countDown();
                releaseFirst.await(2, TimeUnit.SECONDS);
            }
            lastObserved.set(event.requestId());
            if ("request-2".equals(event.requestId())) {
                acceptedSecond.countDown();
            }
            return ConsumerEffect.done();
        });

        var executor = Executors.newSingleThreadExecutor();
        try {
            ActorRef<ConsumerWorkerActor.Command> worker = system.systemActorOf(
                    ConsumerWorkerActor.create(consumer, executor, Duration.ofSeconds(1), 1),
                    "worker-bounded",
                    Props.empty()
            );

            worker.tell(new ConsumerWorkerActor.Process(event("request-1")));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            worker.tell(new ConsumerWorkerActor.Process(event("request-2")));
            worker.tell(new ConsumerWorkerActor.Process(event("request-3")));
            releaseFirst.countDown();

            assertTrue(acceptedSecond.await(2, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertEquals("request-2", lastObserved.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usesRuntimeDefaultTimeoutWhenConsumerDoesNotOverride() {
        SpyConsumer consumer = new SpyConsumer("spy", event -> ConsumerEffect.done());

        Duration timeout = ConsumerWorkerActor.resolveProcessingTimeout(consumer, Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(5), timeout);
    }

    @Test
    void exactDefaultValueConsumerTimeoutOverridesRuntimeDefault() {
        SpyConsumer consumer = new SpyConsumer("spy", event -> ConsumerEffect.done()) {
            @Override
            public Duration processingTimeout() {
                return AgentConsumer.DEFAULT_PROCESSING_TIMEOUT;
            }
        };

        Duration timeout = ConsumerWorkerActor.resolveProcessingTimeout(consumer, Duration.ofSeconds(5));

        assertEquals(AgentConsumer.DEFAULT_PROCESSING_TIMEOUT, timeout);
    }

    private static AgentCompletedEvent event(String requestId) {
        return new AgentCompletedEvent(
                requestId,
                "tenant-a",
                "gateway",
                "input",
                "output",
                com.example.agent.runtime.AgentStatus.COMPLETED,
                List.of(),
                10L,
                Instant.now()
        );
    }

    private static class SpyConsumer extends AgentConsumer {
        private final String consumerId;
        private final ThrowingHandler handler;

        private SpyConsumer(String consumerId, ThrowingHandler handler) {
            this.consumerId = consumerId;
            this.handler = handler;
        }

        @Override
        public String consumerId() {
            return consumerId;
        }

        @Override
        public ConsumerEffect onAgentCompleted(AgentCompletedEvent event) {
            try {
                return handler.handle(event);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        ConsumerEffect handle(AgentCompletedEvent event) throws Exception;
    }
}
