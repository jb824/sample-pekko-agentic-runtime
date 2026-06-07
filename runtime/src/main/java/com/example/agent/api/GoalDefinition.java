package com.example.agent.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record GoalDefinition(
        String name,
        String description,
        String instructionsTemplate,
        int maxIterations,
        List<GoalRule> rules
) {
    public GoalDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("task name must not be blank");
        }
        description = description == null ? "" : description;
        instructionsTemplate = instructionsTemplate == null ? "{{input}}" : instructionsTemplate;
        maxIterations = Math.max(1, maxIterations);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public GoalRequest request(String input) {
        String value = input == null ? "" : input;
        String rendered = instructionsTemplate.replace("{{input}}", value);
        return GoalRequest.of(this).instructions(rendered).build();
    }

    public GoalRuleResult validate(GoalRequest request) {
        for (GoalRule rule : rules) {
            GoalRuleResult result = rule.validate(request);
            if (!result.valid()) {
                return result;
            }
        }
        return GoalRuleResult.ok();
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private String instructionsTemplate = "{{input}}";
        private int maxIterations = 4;
        private final List<GoalRule> rules = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder describedAs(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder template(String instructionsTemplate) {
            this.instructionsTemplate = instructionsTemplate == null ? "{{input}}" : instructionsTemplate;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder rule(GoalRule rule) {
            this.rules.add(Objects.requireNonNull(rule));
            return this;
        }

        public GoalDefinition build() {
            return new GoalDefinition(name, description, instructionsTemplate, maxIterations, rules);
        }
    }
}
