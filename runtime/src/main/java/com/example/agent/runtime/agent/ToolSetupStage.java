package com.example.agent.runtime.agent;

import com.example.agent.runtime.tool.ToolProtocol;
import org.apache.pekko.actor.typed.ActorRef;

final class ToolSetupStage {
    boolean requiresRegistration(DefaultAgentRunState state) {
        return !state.system().toolDefinitions().isEmpty();
    }

    ToolProtocol.RegisterTools registerTools(
            DefaultAgentRunState state,
            ActorRef<ToolProtocol.ToolsRegistered> replyTo
    ) {
        return new ToolProtocol.RegisterTools(
                state.request().requestId() + ":register",
                state.request().tenantId(),
                state.system().toolDefinitions(),
                replyTo);
    }
}
