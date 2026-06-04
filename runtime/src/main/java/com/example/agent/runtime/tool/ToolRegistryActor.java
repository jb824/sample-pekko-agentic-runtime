package com.example.agent.runtime.tool;

import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolRequest;
import com.example.agent.api.AgentToolResult;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolRegistryActor extends AbstractBehavior<ToolProtocol.Command> {
    private final Map<String, AgentToolDefinition> definitions;

    public static Behavior<ToolProtocol.Command> create(List<AgentToolDefinition> definitions) {
        return Behaviors.setup(context -> new ToolRegistryActor(context, definitions));
    }

    private ToolRegistryActor(ActorContext<ToolProtocol.Command> context, List<AgentToolDefinition> definitions) {
        super(context);
        this.definitions = index(definitions);
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        Objects.requireNonNull(command.toolName());
        AgentToolDefinition definition = definitions.get(command.toolName());
        if (definition == null) {
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    "",
                    List.of(),
                    new IllegalArgumentException("Unregistered tool: " + command.toolName())
            ));
            return this;
        }

        AgentToolRequest request = new AgentToolRequest(
                command.requestId(),
                command.tenantId(),
                command.toolName(),
                command.userInput(),
                command.arguments()
        );
        definition.handler().invoke(request).whenComplete((result, failure) -> {
            if (failure != null) {
                command.replyTo().tell(new ToolProtocol.ToolResult(
                        command.requestId(),
                        command.toolName(),
                        "",
                        List.of(),
                        failure
                ));
                return;
            }
            AgentToolResult resolved = result == null ? AgentToolResult.success("") : result;
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    resolved.output(),
                    resolved.sources(),
                    resolved.error()
            ));
        });
        return this;
    }

    private static Map<String, AgentToolDefinition> index(List<AgentToolDefinition> definitions) {
        Map<String, AgentToolDefinition> indexed = new LinkedHashMap<>();
        if (definitions != null) {
            for (AgentToolDefinition definition : definitions) {
                if (indexed.containsKey(definition.name())) {
                    throw new IllegalArgumentException("Duplicate tool definition: " + definition.name());
                }
                indexed.put(definition.name(), definition);
            }
        }
        return Map.copyOf(indexed);
    }
}
