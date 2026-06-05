package com.example.agent.runtime.tool;

import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolResult;
import org.apache.pekko.actor.typed.ActorRef;

import java.util.List;
import java.util.Map;

public final class ToolProtocol {
    private ToolProtocol() {
    }

    public sealed interface Command permits RegisterTools, InvokeTool, WrappedResult {
    }

    public record RegisterTools(
            String requestId,
            String tenantId,
            List<AgentToolDefinition> toolDefinitions,
            ActorRef<ToolsRegistered> replyTo
    ) implements Command {
        public RegisterTools {
            toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        }
    }

    public record ToolsRegistered(
            String requestId,
            int registeredCount,
            int duplicateCount
    ) {
    }

    public record InvokeTool(
            String requestId,
            String tenantId,
            String agentName,
            String toolName,
            String userInput,
            Map<String, String> arguments,
            ActorRef<ToolResult> replyTo
    ) implements Command {
        public InvokeTool {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public record ToolResult(
            String requestId,
            String agentName,
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

    record WrappedResult(
            InvokeTool command,
            AgentToolResult result,
            Throwable failure
    ) implements Command {
    }
}
