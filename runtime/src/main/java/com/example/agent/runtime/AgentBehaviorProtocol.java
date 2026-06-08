package com.example.agent.runtime;

// Internal behavior decision messages for experimental agent planning loops.
public final class AgentBehaviorProtocol {
    private AgentBehaviorProtocol() {
    }

    public sealed interface Decision permits Plan, Act, Observe, Finalize {
    }

    public record Plan(String requestId, String input) implements Decision {
    }

    public record Act(String toolName, String query) implements Decision {
    }

    public record Observe(String observation) implements Decision {
    }

    public record Finalize(String answer) implements Decision {
    }
}
