package com.example.agent.runtime.consumer;

import java.util.Optional;

public final class ConsumerEffect {
    private static final ConsumerEffect DONE = new ConsumerEffect(null);

    private final String failureReason;

    private ConsumerEffect(String failureReason) {
        this.failureReason = failureReason;
    }

    public static ConsumerEffect done() {
        return DONE;
    }

    public static ConsumerEffect fail(String reason) {
        String normalized = reason == null || reason.isBlank() ? "consumer failed" : reason;
        return new ConsumerEffect(normalized);
    }

    public boolean isDone() {
        return failureReason == null;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }
}
