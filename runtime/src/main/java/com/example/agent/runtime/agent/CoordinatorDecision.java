package com.example.agent.runtime.agent;

import com.example.agent.api.Agent;
import com.example.agent.protocol.AgentResult;
import com.example.agent.runtime.memory.AgentMemoryEventType;

import java.util.List;

sealed interface CoordinatorDecision permits CoordinatorDecision.RunDelegate,
        CoordinatorDecision.RouteWithGateway,
        CoordinatorDecision.SynthesizeGateway,
        CoordinatorDecision.CompleteRun {

    DefaultAgentRunState state();

    default List<MemoryWrite> memoryWrites() {
        return List.of();
    }

    record RunDelegate(DefaultAgentRunState state, Agent delegate) implements CoordinatorDecision {
    }

    record RouteWithGateway(DefaultAgentRunState state, String prompt) implements CoordinatorDecision {
    }

    record SynthesizeGateway(DefaultAgentRunState state) implements CoordinatorDecision {
    }

    record CompleteRun(DefaultAgentRunState state, AgentResult result, List<MemoryWrite> memoryWrites) implements CoordinatorDecision {
        public CompleteRun {
            memoryWrites = memoryWrites == null ? List.of() : List.copyOf(memoryWrites);
        }
    }

    record MemoryWrite(AgentMemoryEventType type, String content) {
    }
}
