package com.example.agent.api;

@FunctionalInterface
public interface AgentTaskRule {
    AgentTaskRuleResult validate(AgentTaskRequest request);

    static AgentTaskRule nonEmptyInstructions() {
        return request -> request.instructions().isBlank()
                ? AgentTaskRuleResult.invalid("task instructions must not be blank")
                : AgentTaskRuleResult.ok();
    }

    static AgentTaskRule maxInstructionChars(int maxChars) {
        int limit = Math.max(1, maxChars);
        return request -> request.instructions().length() > limit
                ? AgentTaskRuleResult.invalid("task instructions exceed " + limit + " characters")
                : AgentTaskRuleResult.ok();
    }
}
