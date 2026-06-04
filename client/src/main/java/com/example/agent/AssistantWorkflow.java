package com.example.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentWorkflow;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Task;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public final class AssistantWorkflow implements AgentWorkflow {
    private static final String TASK_TYPE = "agent.request";

    private final AgentSystem system;
    private final Duration timeout;

    public AssistantWorkflow(String... tools) {
        List<String> enabledTools = tools == null ? List.of() : Arrays.stream(tools)
                .filter(tool -> tool != null && !tool.isBlank())
                .toList();
        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer the user directly and use available tools when useful.")
                .uses(enabledTools.toArray(String[]::new))
                .memory(AgentMemoryConfig.recentEvents(20))
                .build();

        GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                .accepts(Task.of(TASK_TYPE).maxIterations(1).build())
                .delegatesTo(assistant)
                .instructedBy("Delegate the task to the assistant and return the final answer.")
                .memory(AgentMemoryConfig.recentEvents(20))
                .build();

        this.system = AgentSystem.builder()
                .entrypoint(gateway)
                .agent(assistant)
                .build();
        this.timeout = Duration.ofSeconds(60);
    }

    @Override
    public AgentSystem system() {
        return system;
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }
}
