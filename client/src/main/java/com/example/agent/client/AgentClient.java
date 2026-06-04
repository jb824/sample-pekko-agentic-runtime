package com.example.agent.client;

import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentRunContext;
import com.example.agent.runtime.AgentResult;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class AgentClient implements AutoCloseable {
    private final AgentRuntime runtime;

    private AgentClient(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public static AgentClient create() {
        return new AgentClient(AgentRuntime.builder().build());
    }

    public CompletionStage<AgentResult> run(AgentWorkflow workflow, String input) {
        return run(UUID.randomUUID().toString(), workflow, input, workflow.timeout());
    }

    public CompletionStage<AgentResult> run(String requestId, AgentWorkflow workflow, String input, Duration timeout) {
        return runtime.run(requestId, workflow.system(), workflow.task(input), timeout);
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, AgentWorkflow workflow, String input) {
        return run(context, UUID.randomUUID().toString(), workflow, input, workflow.timeout());
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, String requestId, AgentWorkflow workflow, String input, Duration timeout) {
        return runtime.run(context, requestId, workflow.system(), workflow.task(input), timeout);
    }

    public AgentRun start(AgentWorkflow workflow, String input) {
        return start(UUID.randomUUID().toString(), workflow, input, workflow.timeout());
    }

    public AgentRun start(String instanceId, AgentWorkflow workflow, String input, Duration timeout) {
        String taskId = runtime.componentClient()
                .forGatewayAgent(workflow.system(), instanceId)
                .runSingleTask(workflow.task(input), timeout);
        return new AgentRun(runtime.componentClient(), taskId);
    }

    @Override
    public void close() {
        runtime.close();
    }
}
