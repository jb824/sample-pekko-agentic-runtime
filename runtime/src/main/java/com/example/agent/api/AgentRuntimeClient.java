package com.example.agent.api;

import com.example.agent.protocol.AgentResult;
import com.example.agent.config.AppConfig;
import com.example.agent.runtime.consumer.AgentConsumer;

import java.time.Duration;
import java.util.ArrayList;
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
        return runtime.run(requestId, workflow.system(), workflow.goal(input), timeout);
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, AgentWorkflow workflow, String input) {
        return run(context, UUID.randomUUID().toString(), workflow, input, workflow.timeout());
    }

    public CompletionStage<AgentResult> run(AgentRunContext context, String requestId, AgentWorkflow workflow, String input, Duration timeout) {
        return runtime.run(context, requestId, workflow.system(), workflow.goal(input), timeout);
    }

    public GoalRun start(AgentWorkflow workflow, String input) {
        return start(UUID.randomUUID().toString(), workflow, input, workflow.timeout());
    }

    public GoalRun start(String instanceId, AgentWorkflow workflow, String input, Duration timeout) {
        String goalId = runtime.componentClient()
                .forGatewayAgent(workflow.system(), instanceId)
                .runSingleGoal(workflow.goal(input), timeout);
        return new GoalRun(runtime.componentClient(), goalId);
    }

    @Override
    public void close() {
        runtime.close();
    }

    public static final class Builder {
        private AppConfig config = AppConfig.fromEnvironment();
        private final List<AgentConsumer> consumers = new ArrayList<>();

        public Builder config(AppConfig config) {
            this.config = Objects.requireNonNull(config);
            return this;
        }

        public Builder consumer(AgentConsumer consumer) {
            this.consumers.add(Objects.requireNonNull(consumer));
            return this;
        }

        public AgentRuntimeClient build() {
            AgentRuntime.Builder builder = AgentRuntime.builder().config(config);
            consumers.forEach(builder::consumer);
            return new AgentRuntimeClient(builder.build());
        }
    }
}
