package com.example.agent.client;

import com.example.agent.api.AgentComponentClient;
import com.example.agent.api.AgentTaskState;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class AgentRun {
    private final AgentComponentClient componentClient;
    private final String taskId;

    AgentRun(AgentComponentClient componentClient, String taskId) {
        this.componentClient = Objects.requireNonNull(componentClient);
        this.taskId = Objects.requireNonNull(taskId);
    }

    public String taskId() {
        return taskId;
    }

    public AgentTaskState get() {
        return getAsync().toCompletableFuture().join();
    }

    public CompletionStage<AgentTaskState> getAsync() {
        return componentClient.forTask(taskId).getAsync();
    }
}
