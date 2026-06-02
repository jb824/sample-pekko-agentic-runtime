package com.example.agent.tool;

import com.example.agent.config.AppConfig;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;
import java.util.Map;

public final class ToolRegistryActor extends AbstractBehavior<ToolProtocol.Command> {
    private final Map<String, ToolDefinition> definitions;

    public static Behavior<ToolProtocol.Command> create(AppConfig config) {
        return Behaviors.setup(context -> new ToolRegistryActor(context, ToolDefinitionCatalog.create(context, config)));
    }

    private ToolRegistryActor(ActorContext<ToolProtocol.Command> context, Map<String, ToolDefinition> definitions) {
        super(context);
        this.definitions = Objects.requireNonNull(definitions);
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        Objects.requireNonNull(command.toolName());
        ToolDefinition definition = definitions.get(command.toolName());
        if (definition == null) {
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    "",
                    new IllegalArgumentException("Unknown tool: " + command.toolName())
            ));
            return this;
        }
        definition.invoker().invoke(command);
        return this;
    }
}
