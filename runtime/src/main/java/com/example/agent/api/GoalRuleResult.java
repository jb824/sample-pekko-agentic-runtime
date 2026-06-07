package com.example.agent.api;

public record GoalRuleResult(boolean valid, String message) {
    public static GoalRuleResult ok() {
        return new GoalRuleResult(true, "");
    }

    public static GoalRuleResult invalid(String message) {
        return new GoalRuleResult(false, message == null ? "invalid goal" : message);
    }
}
