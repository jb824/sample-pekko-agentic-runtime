package com.example.agent.runtime.consumer;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalRequest;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Goal;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentRuntimeConsumerIntegrationTest {
    @Test
    void registeredConsumerReceivesCompletedEventAfterRun() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<AgentCompletedEvent> observed = new AtomicReference<>();
        SpyConsumer consumer = new SpyConsumer(delivered, observed);

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(new FixedModel())
                .consumer(consumer)
                .telemetryEnabled(false)
                .build()) {
            var result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-1",
                            system(),
                            GoalRequest.of("agent.request").instructions("hello").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();

            assertTrue(result.isSuccess());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals("request-1", observed.get().requestId());
            assertEquals("tenant-a", observed.get().tenantId());
            assertEquals("gateway", observed.get().gatewayName());
            assertEquals("hello", observed.get().originalInput());
            assertEquals(com.example.agent.runtime.AgentStatus.COMPLETED, observed.get().status());
        }
    }

    @Test
    void registeredConsumerReceivesValidationFailureEvent() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<AgentCompletedEvent> observed = new AtomicReference<>();
        SpyConsumer consumer = new SpyConsumer(delivered, observed);

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(new FixedModel())
                .consumer(consumer)
                .telemetryEnabled(false)
                .build()) {
            var result = runtime.run(
                            AgentRunContext.tenant("tenant-a"),
                            "request-invalid",
                            system(),
                            GoalRequest.of("unknown.task").instructions("bad").build(),
                            Duration.ofSeconds(5)
                    )
                    .toCompletableFuture()
                    .join();

            assertEquals(com.example.agent.runtime.AgentStatus.FAILED_SYSTEM, result.status());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals("request-invalid", observed.get().requestId());
            assertEquals("bad", observed.get().originalInput());
            assertEquals(com.example.agent.runtime.AgentStatus.FAILED_SYSTEM, observed.get().status());
        }
    }

    @Test
    void validationFailureFutureCompletesAfterConsumerProcessesEvent() throws Exception {
        AtomicBoolean processed = new AtomicBoolean(false);

        try (AgentRuntime runtime = AgentRuntime.builder()
                .chatModel(new FixedModel())
                .consumer(new DelayedConsumer(processed))
                .telemetryEnabled(false)
                .build()) {
            var result = runtime.run(
                    AgentRunContext.tenant("tenant-a"),
                    "request-invalid-ack",
                    system(),
                    GoalRequest.of("unknown.task").instructions("bad").build(),
                    Duration.ofSeconds(5)
            ).toCompletableFuture();

            Thread.sleep(50L);
            assertFalse(result.isDone());

            assertEquals(com.example.agent.runtime.AgentStatus.FAILED_SYSTEM, result.join().status());
            assertTrue(processed.get());
        }
    }

    private static AgentSystem system() {
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer.")
                .memory(AgentMemoryConfig.disabled())
                .build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(Goal.of("agent.request").maxIterations(1).build())
                .delegatesTo(assistant)
                .memory(AgentMemoryConfig.disabled())
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static final class FixedModel implements ChatModel {
        private int calls;

        @Override
        public String chat(String prompt) {
            calls++;
            return calls == 1 ? "FINAL: assistant answer" : "gateway answer";
        }
    }

    private static final class SpyConsumer extends AgentConsumer {
        private final CountDownLatch delivered;
        private final AtomicReference<AgentCompletedEvent> observed;

        private SpyConsumer(CountDownLatch delivered, AtomicReference<AgentCompletedEvent> observed) {
            this.delivered = delivered;
            this.observed = observed;
        }

        @Override
        public String consumerId() {
            return "spy-consumer";
        }

        @Override
        public ConsumerEffect onAgentCompleted(AgentCompletedEvent event) {
            observed.set(event);
            delivered.countDown();
            return ConsumerEffect.done();
        }
    }

    private static final class DelayedConsumer extends AgentConsumer {
        private final AtomicBoolean processed;

        private DelayedConsumer(AtomicBoolean processed) {
            this.processed = processed;
        }

        @Override
        public String consumerId() {
            return "delayed-consumer";
        }

        @Override
        public ConsumerEffect onAgentCompleted(AgentCompletedEvent event) {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            processed.set(true);
            return ConsumerEffect.done();
        }
    }
}
