package com.example.agent.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class PekkoRuntimeConfig {
    public static final String LLM_DISPATCHER_PATH = "agent.llm-dispatcher";

    private PekkoRuntimeConfig() {
    }

    public static Config forAppConfig(AppConfig config) {
        String override = """
                agent.llm-dispatcher.thread-pool-executor.fixed-pool-size = %d
                """.formatted(Math.max(1, config.llmThreads()));
        return ConfigFactory.parseString(override).withFallback(ConfigFactory.load());
    }
}
