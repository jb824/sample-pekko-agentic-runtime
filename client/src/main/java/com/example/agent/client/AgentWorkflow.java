package com.example.agent.client;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTask;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Task;

import java.time.Duration;
import java.util.Arrays;

public record AgentWorkflow(AgentSystem system, String taskType, Duration timeout) {
    public static AgentWorkflow singleAgent(String name, String instructions, String taskType, String... tools) {
        Agent agent = Agent.named(name)
                .instructedBy(instructions)
                .uses(tools)
                .memory(AgentMemoryConfig.recentEvents(20))
                .build();
        GatewayAgent gateway = GatewayAgent.named(name + "-gateway")
                .accepts(Task.of(taskType).maxIterations(1).build())
                .delegatesTo(agent)
                .instructedBy("Delegate the task to the agent and return the final answer.")
                .memory(AgentMemoryConfig.recentEvents(20))
                .build();
        return new AgentWorkflow(
                AgentSystem.builder().entrypoint(gateway).agent(agent).build(),
                taskType,
                Duration.ofSeconds(60)
        );
    }

    public AgentWorkflow withTimeout(Duration timeout) {
        return new AgentWorkflow(system, taskType, timeout);
    }

    AgentTask task(String input) {
        return AgentTask.of(taskType).instructions(input).build();
    }

    @Override
    public String toString() {
        return "AgentWorkflow[entrypoint=%s, agents=%s, taskType=%s, timeout=%s]".formatted(
                system.entrypoint().name(),
                Arrays.toString(system.agents().stream().map(Agent::name).toArray()),
                taskType,
                timeout
        );
    }
}
