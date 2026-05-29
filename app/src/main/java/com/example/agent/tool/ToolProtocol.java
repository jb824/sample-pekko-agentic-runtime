package com.example.agent.tool;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.Map;

public final class ToolProtocol {
    private ToolProtocol() {
    }

    public sealed interface Command permits InvokeTool {
    }

    public record InvokeTool(
            String requestId,
            String toolName,
            Map<String, String> arguments,
            ActorRef<ToolResult> replyTo
    ) implements Command {
    }

    public record ToolResult(
            String requestId,
            String toolName,
            String output,
            Throwable error
    ) {
        public boolean isSuccess() {
            return error == null;
        }
    }
}
