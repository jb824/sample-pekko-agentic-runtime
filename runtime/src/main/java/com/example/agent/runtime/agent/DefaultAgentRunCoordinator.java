package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.api.GatewayAgent;
import com.example.agent.llm.LlmProtocol;
import com.example.agent.protocol.AgentError;
import com.example.agent.protocol.AgentResult;
import com.example.agent.protocol.AgentStatus;
import com.example.agent.runtime.memory.AgentMemoryEventType;

import java.util.List;
import java.util.stream.Collectors;

final class DefaultAgentRunCoordinator {
    private final PromptBudget promptBudget;

    DefaultAgentRunCoordinator(PromptBudget promptBudget) {
        this.promptBudget = promptBudget == null ? PromptBudget.disabled() : promptBudget;
    }

    CoordinatorDecision next(DefaultAgentRunState state) {
        if (state.system().entrypoint().orchestrationMode() == GatewayAgent.OrchestrationMode.MODEL_DRIVEN) {
            return routeWithGateway(state);
        }
        if (state.delegateIndex() >= state.delegates().size()) {
            return new CoordinatorDecision.SynthesizeGateway(state);
        }
        return new CoordinatorDecision.RunDelegate(state, state.delegates().get(state.delegateIndex()));
    }

    CoordinatorDecision onStepResult(DefaultAgentRunState state, AgentStepActor.Result result) {
        DefaultAgentRunState next = state.advanceDelegate();
        if (result.isSuccess()) {
            next = next.withStepResult(result);
        }
        return next(next);
    }

    CoordinatorDecision onGatewayRouteResponse(DefaultAgentRunState state, LlmProtocol.Response response) {
        if (!response.isSuccess()) {
            String err = response.error() == null ? "gateway route LLM failure" : response.error().getMessage();
            return complete(
                    state,
                    new AgentResult(
                            state.request().requestId(),
                            AgentStatus.FAILED_SYSTEM,
                            "",
                            state.distinctSources(),
                            List.of(new AgentError("gateway_route_failed", err, true, "gateway"))),
                    new CoordinatorDecision.MemoryWrite(AgentMemoryEventType.FAILURE, err));
        }
        String finalAnswer = routeFinalText(response.text());
        if (!finalAnswer.isBlank()) {
            return complete(
                    state,
                    new AgentResult(
                            state.request().requestId(),
                            AgentStatus.COMPLETED,
                            withSources(state, finalAnswer),
                            state.distinctSources(),
                            List.of()),
                    new CoordinatorDecision.MemoryWrite(AgentMemoryEventType.FINAL_ANSWER, finalAnswer));
        }
        String delegateName = routeDelegateName(response.text());
        Agent delegate = state.delegates().stream()
                .filter(candidate -> candidate.name().equals(delegateName))
                .findFirst()
                .orElse(null);
        if (delegate == null) {
            return complete(state, new AgentResult(
                    state.request().requestId(),
                    AgentStatus.FAILED_SYSTEM,
                    "",
                    state.distinctSources(),
                    List.of(new AgentError(
                            "gateway_route_invalid",
                            "Gateway selected unknown delegate: " + delegateName,
                            true,
                            "gateway"))));
        }
        return new CoordinatorDecision.RunDelegate(state, delegate);
    }

    CoordinatorDecision onGatewayResponse(DefaultAgentRunState state, LlmProtocol.Response response) {
        if (!response.isSuccess()) {
            String err = response.error() != null ? response.error().getMessage() : "gateway LLM failure";
            return complete(
                    state,
                    new AgentResult(
                            state.request().requestId(),
                            AgentStatus.FAILED_SYSTEM,
                            "",
                            state.distinctSources(),
                            List.of(new AgentError("gateway_synthesis_failed", err, false, "gateway"))),
                    new CoordinatorDecision.MemoryWrite(AgentMemoryEventType.FAILURE, err));
        }
        String answer = response.text();
        return complete(
                state,
                new AgentResult(
                        state.request().requestId(),
                        AgentStatus.COMPLETED,
                        withSources(state, answer),
                        state.distinctSources(),
                        List.of()),
                new CoordinatorDecision.MemoryWrite(AgentMemoryEventType.USER_TASK, state.request().input()),
                new CoordinatorDecision.MemoryWrite(AgentMemoryEventType.FINAL_ANSWER, answer));
    }

    CoordinatorDecision onTimeout(DefaultAgentRunState state) {
        return complete(state, new AgentResult(
                state.request().requestId(),
                AgentStatus.TIMEOUT,
                "",
                state.distinctSources(),
                List.of(new AgentError("executor_timeout", "Agent system execution timed out.", true, "executor"))));
    }

    AgentResult contextBudgetFailure(DefaultAgentRunState state, String owner) {
        return new AgentResult(
                state.request().requestId(),
                AgentStatus.FAILED_SYSTEM,
                "",
                state.distinctSources(),
                List.of(new AgentError(
                        "context_budget_exceeded",
                        "Prompt context exceeds configured budget before LLM call: max_prompt_chars="
                                + promptBudget.maxPromptChars(),
                        false,
                        owner)));
    }

    private CoordinatorDecision routeWithGateway(DefaultAgentRunState state) {
        if (state.delegateIndex() >= state.system().entrypoint().acceptedGoal().maxIterations()) {
            return new CoordinatorDecision.SynthesizeGateway(state);
        }
        String prompt = GatewayPromptBuilder.buildRoute(
                state.request(),
                state.system().entrypoint(),
                state.delegates(),
                state.delegateOutputs(),
                state.ragContext(),
                promptBudget);
        if (prompt.length() > promptBudget.maxPromptChars()) {
            return complete(state, contextBudgetFailure(state, "gateway"));
        }
        return new CoordinatorDecision.RouteWithGateway(state, prompt);
    }

    private static CoordinatorDecision.CompleteRun complete(
            DefaultAgentRunState state,
            AgentResult result,
            CoordinatorDecision.MemoryWrite... memoryWrites
    ) {
        return new CoordinatorDecision.CompleteRun(state, result, List.of(memoryWrites));
    }

    private static String withSources(DefaultAgentRunState state, String answer) {
        List<String> distinct = state.distinctSources();
        if (distinct.isEmpty() || distinct.stream().anyMatch(answer::contains)) {
            return answer;
        }
        return answer.stripTrailing() + "\n\nSources:\n"
                + distinct.stream().map(source -> "- " + source).collect(Collectors.joining("\n"));
    }

    private static String routeDelegateName(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (String line : text.strip().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "DELEGATE:", 0, "DELEGATE:".length())) {
                return trimmed.substring("DELEGATE:".length()).trim();
            }
        }
        return "";
    }

    private static String routeFinalText(String text) {
        return ToolCallParser.embeddedFinalText(text)
                .orElseGet(() -> text != null && text.strip().regionMatches(true, 0, "FINAL:", 0, "FINAL:".length())
                        ? ToolCallParser.finalText(text)
                        : "");
    }
}
