package com.example.agent;

import com.example.agent.protocol.AgentStatus;
import com.example.agent.runtime.consumer.AgentCompletedEvent;
import com.example.agent.runtime.consumer.ConsumerEffect;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AssistantResponseReviewConsumerTest {
    @Test
    void reviewsSuccessfulAssistantResponseAgainstOriginalQuestion() {
        EvalConsumer consumer = new EvalConsumer(new FixedReviewModel());

        ConsumerEffect effect = consumer.onAgentCompleted(new AgentCompletedEvent(
                "request-1",
                "tenant-a",
                "assistant-gateway",
                "What is Apache Pekko?",
                "Apache Pekko is an actor toolkit.",
                AgentStatus.COMPLETED,
                List.of("https://pekko.apache.org"),
                10L,
                Instant.now()
        ));

        assertTrue(effect.isDone());
        EvalConsumer.ReviewReport report = consumer.awaitReview("request-1", Duration.ofSeconds(1)).orElseThrow();
        assertTrue(report.passed());
        assertEquals(8.5, report.score());
        assertTrue(report.reason().contains("answers the question"));
    }

    @Test
    void skipsFailedAgentResults() {
        EvalConsumer consumer = new EvalConsumer(new FixedReviewModel());

        ConsumerEffect effect = consumer.onAgentCompleted(new AgentCompletedEvent(
                "request-2",
                "tenant-a",
                "assistant-gateway",
                "Question",
                "",
                AgentStatus.FAILED_SYSTEM,
                List.of(),
                10L,
                Instant.now()
        ));

        assertTrue(effect.isDone());
        assertTrue(consumer.awaitReview("request-2", Duration.ofMillis(50)).isEmpty());
    }

    private static final class FixedReviewModel implements ChatModel {
        @Override
        public String chat(String prompt) {
            assertTrue(prompt.contains("User question:"));
            assertTrue(prompt.contains("Assistant response:"));
            return """
                    PASS: true
                    SCORE: 8.5/10
                    REASON: The response answers the question but could include more detail.
                    """;
        }
    }
}
