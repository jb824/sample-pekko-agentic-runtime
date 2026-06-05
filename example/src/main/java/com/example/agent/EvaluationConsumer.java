package com.example.agent;

import com.example.agent.runtime.consumer.AgentCompletedEvent;
import com.example.agent.runtime.consumer.AgentConsumer;
import com.example.agent.runtime.consumer.ConsumerEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class EvaluationConsumer extends AgentConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationConsumer.class);

    private final Evaluator evaluator;
    private final Set<String> processedRequestIds = ConcurrentHashMap.newKeySet();

    public EvaluationConsumer(Evaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    @Override
    public String consumerId() {
        return "evaluation-consumer";
    }

    @Override
    public ConsumerEffect onAgentCompleted(AgentCompletedEvent event) {
        if (!event.status().isSuccess()) {
            return ConsumerEffect.done();
        }
        if (!processedRequestIds.add(event.requestId())) {
            return ConsumerEffect.done();
        }
        EvaluationResult result = evaluator.evaluate(event.originalInput(), event.finalOutput());
        if (result.passed()) {
            LOG.debug("Evaluation passed request_id={} score={}", event.requestId(), result.score());
        } else {
            LOG.warn("Evaluation failed request_id={} score={} reason={}",
                    event.requestId(), result.score(), result.reason());
        }
        return ConsumerEffect.done();
    }

    @FunctionalInterface
    public interface Evaluator {
        EvaluationResult evaluate(String input, String output);
    }

    public record EvaluationResult(boolean passed, double score, String reason) {
        public EvaluationResult {
            score = Math.max(0.0, Math.min(1.0, score));
            reason = reason == null ? "" : reason;
        }
    }
}
