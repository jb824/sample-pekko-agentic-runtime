package com.example.agent.runtime.tool;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.List;
import java.util.Map;

public final class ToolProtocol {
    private ToolProtocol() {
    }

    public sealed interface Command permits InvokeTool {
    }

    public record InvokeTool(
            String requestId,
            String tenantId,
            String toolName,
            String userInput,
            Map<String, String> arguments,
            ActorRef<ToolResult> replyTo
    ) implements Command {
    }

    public record ToolResult(
            String requestId,
            String toolName,
            String output,
            List<String> sources,
            Throwable error
    ) {
        public ToolResult {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
