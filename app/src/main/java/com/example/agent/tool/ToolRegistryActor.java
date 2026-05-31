package com.example.agent.tool;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Map;
import java.util.Objects;

public final class ToolRegistryActor extends AbstractBehavior<ToolProtocol.Command> {
    private final Map<String, ActorRef<ToolProtocol.Command>> toolByName;

    public static Behavior<ToolProtocol.Command> create() {
        return create(new DefaultToolWiring());
    }

    public static Behavior<ToolProtocol.Command> create(ToolWiring toolWiring) {
        return Behaviors.setup(context -> new ToolRegistryActor(context, toolWiring));
    }

    private ToolRegistryActor(ActorContext<ToolProtocol.Command> context, ToolWiring toolWiring) {
        super(context);
        this.toolByName = Map.of(
                TimeToolActor.TOOL_NAME, context.spawn(TimeToolActor.create(toolWiring.timeToolService()), "time-tool"),
                WebSearchToolActor.TOOL_NAME, context.spawn(WebSearchToolActor.create(toolWiring.webSearchToolService()), "web-search-tool"),
                ArxivSearchToolActor.TOOL_NAME, context.spawn(ArxivSearchToolActor.create(toolWiring.arxivSearchToolService()), "arxiv-search-tool"),
                PubMedSearchToolActor.TOOL_NAME, context.spawn(PubMedSearchToolActor.create(toolWiring.pubMedSearchToolService()), "pubmed-search-tool")
        );
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        Objects.requireNonNull(command.toolName());
        ActorRef<ToolProtocol.Command> tool = toolByName.get(command.toolName());
        if (tool == null) {
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    "",
                    new IllegalArgumentException("Unknown tool: " + command.toolName())
            ));
            return this;
        }
        tool.tell(command);
        return this;
    }
}
