package com.example.agent.api;

public record AgentTaskRuleResult(boolean valid, String message) {
    public static AgentTaskRuleResult ok() {
        return new AgentTaskRuleResult(true, "");
    }

    public static AgentTaskRuleResult invalid(String message) {
        return new AgentTaskRuleResult(false, message == null ? "invalid task" : message);
    }
}
