package com.example.agent.config;

import java.time.Duration;
import java.util.List;

public record AppConfig(
        String appMode,
        String llmBackend,
        String ollamaBaseUrl,
        String ollamaModelName,
        String vllmBaseUrl,
        String vllmModelName,
        String vllmApiKey,
        String vllmApiType,
        String vllmSystemPrompt,
        int vllmMaxTokens,
        String vllmTokenizerPath,
        String vllmGrpcHost,
        int vllmGrpcPort,
        boolean vllmGrpcPlaintext,
        double temperature,
        Duration llmTimeout,
        int llmThreads,
        int llmQueueSize,
        int requestCount,
        String workflowMode,
        String enabledTools,
        int maxTools,
        int maxSteps,
        String kafkaBootstrapServers,
        String kafkaTopic,
        String kafkaInputTopic,
        String kafkaOutputTopic,
        String kafkaGroupId,
        String guardianQuery,
        String guardianSection,
        int guardianMaxPages,
        Duration workflowTimeout,
        Duration toolTimeout,
        String testPrompt,
        List<String> testPrompts
) {
    public static AppConfig fromEnvironment() {
        return AppConfigLoader.load();
    }

    public String promptForRequest(int requestNumber) {
        if (testPrompts.isEmpty()) {
            return testPrompt;
        }
        return testPrompts.get((requestNumber - 1) % testPrompts.size());
    }
}
