package com.example.agent.runtime.consumer;

import java.time.Duration;

public abstract class AgentConsumer {
    public static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofSeconds(30);

    public abstract String consumerId();

    public abstract ConsumerEffect onAgentCompleted(AgentCompletedEvent event);

    public Duration processingTimeout() {
        return null;
    }
}
