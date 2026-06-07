package com.example.agent.api;

@FunctionalInterface
public interface GoalRule {
    GoalRuleResult validate(GoalRequest request);

    static GoalRule nonEmptyInstructions() {
        return request -> request.instructions().isBlank()
                ? GoalRuleResult.invalid("goal instructions must not be blank")
                : GoalRuleResult.ok();
    }

    static GoalRule maxInstructionChars(int maxChars) {
        int limit = Math.max(1, maxChars);
        return request -> request.instructions().length() > limit
                ? GoalRuleResult.invalid("goal instructions exceed " + limit + " characters")
                : GoalRuleResult.ok();
    }
}
