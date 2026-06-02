package com.example.agent.client;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class GatewayAgentClient {
    private final AgentClient transport;
    private final AgentSystem system;
    private final String instanceId;

    GatewayAgentClient(AgentClient transport, AgentSystem system, String instanceId) {
        this.transport = Objects.requireNonNull(transport);
        this.system = Objects.requireNonNull(system);
        this.instanceId = Objects.requireNonNull(instanceId);
    }

    public String runSingleTask(AgentTask task) {
        return runSingleTaskAsync(task).toCompletableFuture().join();
    }

    public CompletionStage<String> runSingleTaskAsync(AgentTask task) {
        return transport.startTask(instanceId, system, task.instructions(), Duration.ofSeconds(60))
                .thenApply(AgentTaskState::taskId);
    }

    public String instanceId() {
        return instanceId;
    }
}
