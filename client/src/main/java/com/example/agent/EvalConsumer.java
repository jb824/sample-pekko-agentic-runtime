package com.example.agent;

import com.example.agent.config.AppConfig;
import com.example.agent.llm.ChatModelFactory;
import com.example.agent.runtime.consumer.AgentCompletedEvent;
import com.example.agent.runtime.consumer.AgentConsumer;
import com.example.agent.runtime.consumer.ConsumerEffect;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvalConsumer extends AgentConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvalConsumer.class);
    private static final Pattern PASS_PATTERN = Pattern.compile("(?im)^\\s*PASS\\s*:\\s*(true|false)\\s*$");
    private static final Pattern SCORE_PATTERN = Pattern.compile("(?im)^\\s*SCORE\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*10\\s*$");
    private static final Pattern REASON_PATTERN = Pattern.compile("(?ims)^\\s*REASON\\s*:\\s*(.+)$");

    private final ChatModel reviewModel;
    private final Set<String> processedRequestIds = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<ReviewReport>> reviewsByRequestId = new ConcurrentHashMap<>();

    public static EvalConsumer fromConfig(AppConfig config) {
        return new EvalConsumer(ChatModelFactory.create(config));
    }

    public EvalConsumer(ChatModel reviewModel) {
        this.reviewModel = Objects.requireNonNull(reviewModel);
    }

    @Override
    public String consumerId() {
        return "assistant-response-evaluator";
    }

    @Override
    public Duration processingTimeout() {
        return Duration.ofSeconds(60);
    }

    @Override
    public ConsumerEffect onAgentCompleted(AgentCompletedEvent event) {
        LOGGER.info(
                "Eval consumer received completion request_id={} status={} output_chars={}",
                event.requestId(),
                event.status(),
                event.finalOutput() == null ? 0 : event.finalOutput().length()
        );
        if (!event.status().isSuccess()) {
            return ConsumerEffect.done();
        }
        CompletableFuture<ReviewReport> review = reviewsByRequestId.computeIfAbsent(
                event.requestId(),
                ignored -> new CompletableFuture<>()
        );
        if (!processedRequestIds.add(event.requestId())) {
            return ConsumerEffect.done();
        }
        try {
            String rawReview = reviewModel.chat(reviewPrompt(event));
            ReviewReport report = ReviewReport.from(event.requestId(), rawReview);
            review.complete(report);
            LOGGER.info(
                    "Eval consumer completed request_id={} pass={} score={}",
                    event.requestId(),
                    report.passed(),
                    report.score()
            );
            return ConsumerEffect.done();
        } catch (RuntimeException exception) {
            ReviewReport failed = new ReviewReport(event.requestId(), false, 0.0, exception.getMessage(), "");
            review.complete(failed);
            LOGGER.warn("Eval consumer failed request_id={} error={}", event.requestId(), exception.toString());
            return ConsumerEffect.fail(exception.getMessage());
        }
    }

    public Optional<ReviewReport> awaitReview(String requestId, Duration timeout) {
        CompletableFuture<ReviewReport> review = reviewsByRequestId.computeIfAbsent(
                requestId,
                ignored -> new CompletableFuture<>()
        );
        try {
            return Optional.of(review.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static String reviewPrompt(AgentCompletedEvent event) {
        return """
                You are a review agent. Evaluate whether the assistant response answers the user question.

                Use the original user question as the source of truth. Check correctness, completeness, relevance,
                and whether the assistant invented facts that are not supported by the prompt or cited sources.

                Return exactly this format:
                PASS: true|false
                SCORE: n/10
                REASON: one concise paragraph

                User question:
                %s

                Assistant response:
                %s

                Sources:
                %s
                """.formatted(
                event.originalInput(),
                event.finalOutput(),
                event.sources().isEmpty() ? "None." : String.join("\n", event.sources())
        );
    }

    public record ReviewReport(
            String requestId,
            boolean passed,
            double score,
            String reason,
            String rawReview
    ) {
        private static ReviewReport from(String requestId, String rawReview) {
            return new ReviewReport(
                    requestId,
                    parsePassed(rawReview),
                    parseScore(rawReview),
                    parseReason(rawReview),
                    rawReview == null ? "" : rawReview
            );
        }

        public String format() {
            return "Review request_id=%s pass=%s score=%.1f/10 reason=%s"
                    .formatted(requestId, passed, score, reason);
        }

        private static boolean parsePassed(String rawReview) {
            Matcher matcher = PASS_PATTERN.matcher(rawReview == null ? "" : rawReview);
            return matcher.find() && Boolean.parseBoolean(matcher.group(1).toLowerCase(Locale.ROOT));
        }

        private static double parseScore(String rawReview) {
            Matcher matcher = SCORE_PATTERN.matcher(rawReview == null ? "" : rawReview);
            if (!matcher.find()) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(10.0, Double.parseDouble(matcher.group(1))));
        }

        private static String parseReason(String rawReview) {
            Matcher matcher = REASON_PATTERN.matcher(rawReview == null ? "" : rawReview);
            return matcher.find() ? matcher.group(1).strip() : rawReview == null ? "" : rawReview.strip();
        }
    }
}
