package com.example.agent.runtime.agent;

import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Agent;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.memory.AgentMemoryEvent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class GatewayPromptBuilder {
    private GatewayPromptBuilder() {
    }

    static String build(
            AgentRequest request,
            GatewayAgent gateway,
            Map<String, String> delegateOutputs,
            List<AgentMemoryEvent> memory,
            String ragContext,
            PromptBudget promptBudget
    ) {
        PromptBudget budget = promptBudget == null ? PromptBudget.disabled() : promptBudget;
        int sectionBudget = Math.max(512, budget.maxPromptChars() / 4);
        String delegateContext = delegateOutputs == null || delegateOutputs.isEmpty()
                ? "No delegate outputs."
                : delegateOutputs.entrySet().stream()
                .map(entry -> "Agent " + entry.getKey() + ":\n" + PromptCompactor.fit(entry.getValue(), sectionBudget))
                .collect(Collectors.joining("\n\n"));
        String memorySection = memory == null || memory.isEmpty()
                ? "None."
                : memory.stream()
                .map(event -> "- [" + event.type() + "] " + PromptCompactor.fit(event.content(), Math.max(256, sectionBudget / memory.size())))
                .collect(Collectors.joining("\n"));
        String prompt = """
                You are gateway agent "%s".
                Instructions: %s

                User task:
                %s

                Goal: %s — %s

                Memory from previous sessions:
                %s

                Retrieved knowledge:
                %s

                Delegate agent outputs:
                %s

                Produce the final answer for the user.
                """.formatted(
                safe(gateway.name()),
                safe(gateway.instructions()),
                request.input(),
                safe(gateway.acceptedGoal().name()),
                safe(gateway.acceptedGoal().description()),
                memorySection,
                ragContext == null || ragContext.isBlank() ? "None." : PromptCompactor.fit(ragContext, sectionBudget),
                delegateContext);
        return PromptCompactor.fit(prompt, budget.maxPromptChars());
    }

    static String buildRoute(
            AgentRequest request,
            GatewayAgent gateway,
            List<Agent> delegates,
            Map<String, String> delegateOutputs,
            String ragContext,
            PromptBudget promptBudget
    ) {
        PromptBudget budget = promptBudget == null ? PromptBudget.disabled() : promptBudget;
        int sectionBudget = Math.max(512, budget.maxPromptChars() / 4);
        String delegateList = delegates == null || delegates.isEmpty()
                ? "None."
                : delegates.stream()
                .map(agent -> "- " + agent.name() + ": " + safe(agent.instructions()))
                .collect(Collectors.joining("\n"));
        String priorOutputs = delegateOutputs == null || delegateOutputs.isEmpty()
                ? "None."
                : delegateOutputs.entrySet().stream()
                .map(entry -> "Agent " + entry.getKey() + ":\n" + PromptCompactor.fit(entry.getValue(), sectionBudget))
                .collect(Collectors.joining("\n\n"));
        String prompt = """
                You are gateway agent "%s".
                Instructions: %s

                User task:
                %s

                Goal: %s — %s

                Available delegate agents:
                %s

                Prior delegate outputs:
                %s

                Retrieved knowledge:
                %s

                Choose the next delegate when more work is needed:
                DELEGATE: agent-name

                Return the final user answer when the goal is complete:
                FINAL: answer
                """.formatted(
                safe(gateway.name()),
                safe(gateway.instructions()),
                request.input(),
                safe(gateway.acceptedGoal().name()),
                safe(gateway.acceptedGoal().description()),
                delegateList,
                priorOutputs,
                ragContext == null || ragContext.isBlank() ? "None." : PromptCompactor.fit(ragContext, sectionBudget)
        );
        return PromptCompactor.fit(prompt, budget.maxPromptChars());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
