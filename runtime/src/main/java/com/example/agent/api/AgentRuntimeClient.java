package com.example.agent.api;

import com.example.agent.runtime.AgentResult;
import com.example.agent.config.AppConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class AgentRuntimeClient implements AutoCloseable {
    private final AgentRuntime runtime;

    private AgentRuntimeClient(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public static AgentRuntimeClient create() {
        return new AgentRuntimeClient(AgentRuntime.builder().build());
    }

    public static Builder builder() {
        return new Builder();
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

    public AgentTaskRun start(AgentWorkflow workflow, String input) {
        return start(UUID.randomUUID().toString(), workflow, input, workflow.timeout());
    }

    public AgentTaskRun start(String instanceId, AgentWorkflow workflow, String input, Duration timeout) {
        String taskId = runtime.componentClient()
                .forGatewayAgent(workflow.system(), instanceId)
                .runSingleTask(workflow.task(input), timeout);
        return new AgentTaskRun(runtime.componentClient(), taskId);
    }

    @Override
    public void close() {
        runtime.close();
    }

    public static final class Builder {
        private final List<AgentToolDefinition> tools = new ArrayList<>();
        private AppConfig config = AppConfig.fromEnvironment();

        public Builder config(AppConfig config) {
            this.config = Objects.requireNonNull(config);
            return this;
        }

        public Builder tool(AgentToolDefinition tool) {
            this.tools.add(Objects.requireNonNull(tool));
            return this;
        }

        public Builder tools(AgentToolDefinition... tools) {
            if (tools != null) {
                this.tools.addAll(Arrays.asList(tools));
            }
            return this;
        }

        public Builder tools(List<AgentToolDefinition> tools) {
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public AgentRuntimeClient build() {
            return new AgentRuntimeClient(AgentRuntime.builder().config(config).tools(tools).build());
        }
    }
}
