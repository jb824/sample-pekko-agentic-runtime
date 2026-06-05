package com.example.agent.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class PekkoRuntimeConfig {
    public static final String LLM_DISPATCHER_PATH = "agent.llm-dispatcher";
    public static final String TOOL_DISPATCHER_PATH = "agent.tool-dispatcher";
    public static final String CONSUMER_DISPATCHER_PATH = "agent.consumer-dispatcher";

    private PekkoRuntimeConfig() {
    }

    public static Config forAppConfig(AppConfig config) {
        String override = """
                agent.llm-dispatcher.thread-pool-executor.fixed-pool-size = %d
                agent.tool-dispatcher.thread-pool-executor.fixed-pool-size = %d
                agent.consumer-dispatcher.thread-pool-executor.fixed-pool-size = %d
                """.formatted(
                Math.max(1, config.llmThreads()),
                Math.max(1, config.toolThreads()),
                Math.max(1, config.consumerThreads())
        );
        return ConfigFactory.parseString(override).withFallback(ConfigFactory.load());
    }
}
