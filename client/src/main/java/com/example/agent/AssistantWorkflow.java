package com.example.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.AgentTaskDefinition;
import com.example.agent.api.AgentTaskRule;
import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentWorkflow;
import com.example.agent.api.GatewayAgent;

import java.time.Duration;
import java.util.List;

public final class AssistantWorkflow implements AgentWorkflow {
    private static final AgentTaskDefinition ANSWER_QUESTION = AgentTaskDefinition.named("answer.question")
            .describedAs("Answer a user's question directly and cite tool results when available.")
            .template("Answer the following user question:\n{{input}}")
            .maxIterations(2)
            .rule(AgentTaskRule.nonEmptyInstructions())
            .rule(AgentTaskRule.maxInstructionChars(8000))
            .build();

    private static final AgentTaskDefinition ANSWER_RESPONSE = AgentTaskDefinition.named("answer.response")
            .describedAs("Evaluate the assistant's answer for correctness and helpfulness. Revise the answer if necessary.")
            .template("Evaluate the assistant's answer:\n{{input}}")
            .maxIterations(2)
            .rule(AgentTaskRule.nonEmptyInstructions())
            .rule(AgentTaskRule.maxInstructionChars(8000))
            .build();

    private final AgentSystem system;
    private final Duration timeout;

    public AssistantWorkflow(AgentToolDefinition... tools) {
        List<AgentToolDefinition> enabledTools = tools == null ? List.of() : java.util.Arrays.stream(tools)
                .filter(tool -> tool != null)
                .toList();

        Agent reviewer = Agent.named("reviewer")
                .instructedBy("Review the assistant's answer and provide feedback on its correctness and helpfulness.")
                .accepts(ANSWER_RESPONSE)
                .memory(AgentMemoryConfig.recentEvents(3))
                .uses(enabledTools.toArray(AgentToolDefinition[]::new))
                .build();

        Agent assistant = Agent.named("assistant")
                .instructedBy("Answer the user directly and use available tools when useful. Return results to reviewer for feedback.")
                .accepts(ANSWER_QUESTION)
                .memory(AgentMemoryConfig.recentEvents(3))
                .uses(enabledTools.toArray(AgentToolDefinition[]::new))
                .build();

        GatewayAgent gateway = GatewayAgent.named("assistant-gateway")
                .accepts(ANSWER_QUESTION)
                .delegatesTo(assistant, reviewer)
                .instructedBy("Delegate the task to the assistant and return the final answer.")
                .memory(AgentMemoryConfig.recentEvents(3))
                .build();

        this.system = AgentSystem.builder()
                .entrypoint(gateway)
                .agent(assistant)
                .agent(reviewer)
                .build();
        this.timeout = Duration.ofSeconds(60);
    }

    @Override
    public AgentSystem system() {
        return system;
    }

    @Override
    public AgentTaskDefinition taskDefinition() {
        return ANSWER_QUESTION;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }
}
