package com.example.agent.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmWorkerActorTest {
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(3);

    private final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "llm-worker-test-" + UUID.randomUUID());

    @AfterEach
    void tearDown() {
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    @Test
    void rejectsRequestsBeyondConfiguredConcurrencyAndQueue() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String prompt) {
                invocations.incrementAndGet();
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted", interruptedException);
                }
                return "ok:" + prompt;
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ActorRef<LlmProtocol.Command> worker = system.systemActorOf(
                    LlmWorkerActor.create(model, executor, 1, 1),
                    "llm-worker-under-test",
                    Props.empty()
            );

            CompletionStage<LlmProtocol.Response> first = ask(worker, "first");
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));

            CompletionStage<LlmProtocol.Response> second = ask(worker, "second");
            LlmProtocol.Response third = ask(worker, "third").toCompletableFuture().join();

            assertInstanceOf(RejectedExecutionException.class, third.error());
            assertEquals(1, invocations.get());

            release.countDown();

            assertTrue(first.toCompletableFuture().join().isSuccess());
            assertTrue(second.toCompletableFuture().join().isSuccess());
            assertEquals(2, invocations.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletionStage<LlmProtocol.Response> ask(ActorRef<LlmProtocol.Command> worker, String prompt) {
        return AskPattern.ask(
                worker,
                replyTo -> new LlmProtocol.Ask(prompt, prompt, replyTo),
                ASK_TIMEOUT,
                system.scheduler()
        );
    }
}
