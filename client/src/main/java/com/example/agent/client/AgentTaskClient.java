package com.example.agent.client;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class AgentTaskClient {
    private final AgentClient transport;
    private final String taskId;

    AgentTaskClient(AgentClient transport, String taskId) {
        this.transport = Objects.requireNonNull(transport);
        this.taskId = taskId;
    }

    public AgentTaskState get() {
        return getAsync().toCompletableFuture().join();
    }

    public CompletionStage<AgentTaskState> getAsync() {
        return transport.getTask(taskId);
    }

    public String taskId() {
        return taskId;
    }
}
