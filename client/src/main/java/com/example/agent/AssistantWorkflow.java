package com.example.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentMemoryConfig;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalDefinition;
import com.example.agent.api.GoalRule;
import com.example.agent.api.AgentWorkflow;
import com.example.agent.api.GatewayAgent;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.tools.ScheduleTool;
import com.example.agent.tools.WebTools;

import java.time.Duration;

public final class AssistantWorkflow implements AgentWorkflow {

    private static final GoalDefinition ANSWER_QUESTION = GoalDefinition.named("answer.question")
            .describedAs("Answer a user's question directly and cite tool results when available.")
            .template("Answer the following user question:\n{{input}}")
            .maxIterations(10)
            .rule(GoalRule.nonEmptyInstructions())
            .rule(GoalRule.maxInstructionChars(8000))
            .build();

    private static final ScheduleTool SCHEDULE_TOOLS = new ScheduleTool();
    private static final CalculatorTool CALCULATOR = new CalculatorTool();
    private static final WebTools WEB_TOOLS = new WebTools();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final Agent ASSISTANT = Agent.named("assistant")
            .instructedBy("Answer the user directly and use available tools when useful. Return results to reviewer for feedback.")
            .memory(AgentMemoryConfig.recentEvents(5))
            .usesTools(SCHEDULE_TOOLS, CALCULATOR, WEB_TOOLS)
            .build();

    private static final Agent REVIEWER = Agent.named("reviewer")
//            .instructedBy("Make sure the time format is in HH:MM:SS.")
            .instructedBy("Make sure the result is correct")
            .memory(AgentMemoryConfig.recentEvents(5))
            .usesTools(SCHEDULE_TOOLS, CALCULATOR, WEB_TOOLS)
            .build();

    private static final GatewayAgent ORCHESTRATOR = GatewayAgent.named("orchestrator")
            .accepts(ANSWER_QUESTION)
            .workflowDriven()
            .delegatesTo("assistant", "reviewer")
            .instructedBy("Delegate the task to the assistant, then review before returning final answer.")
            .memory(AgentMemoryConfig.recentEvents(5))
            .build();

    private static final AgentSystem SYSTEM = AgentSystem.builder()
            .entrypoint(ORCHESTRATOR)
            .agents(ASSISTANT, REVIEWER)
            .build();

    public AssistantWorkflow() {
    }

    @Override
    public AgentSystem system() {
        return SYSTEM;
    }

    @Override
    public GoalDefinition goalDefinition() {
        return ANSWER_QUESTION;
    }

    @Override
    public Duration timeout() {
        return TIMEOUT;
    }
}
