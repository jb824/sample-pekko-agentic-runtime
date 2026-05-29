package com.example.agent.tool;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;

public final class ToolRegistryActor extends AbstractBehavior<ToolProtocol.Command> {
    private final ActorRef<ToolProtocol.Command> timeTool;
    private final ActorRef<ToolProtocol.Command> webSearchTool;
    private final ActorRef<ToolProtocol.Command> arxivSearchTool;
    private final ActorRef<ToolProtocol.Command> pubMedSearchTool;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(ToolRegistryActor::new);
    }

    private ToolRegistryActor(ActorContext<ToolProtocol.Command> context) {
        super(context);
        this.timeTool = context.spawn(TimeToolActor.create(), "time-tool");
        this.webSearchTool = context.spawn(WebSearchToolActor.create(), "web-search-tool");
        this.arxivSearchTool = context.spawn(ArxivSearchToolActor.create(), "arxiv-search-tool");
        this.pubMedSearchTool = context.spawn(PubMedSearchToolActor.create(), "pubmed-search-tool");
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        Objects.requireNonNull(command.toolName());
        switch (command.toolName()) {
            case TimeToolActor.TOOL_NAME -> timeTool.tell(command);
            case WebSearchToolActor.TOOL_NAME -> webSearchTool.tell(command);
            case ArxivSearchToolActor.TOOL_NAME -> arxivSearchTool.tell(command);
            case PubMedSearchToolActor.TOOL_NAME -> pubMedSearchTool.tell(command);
            default -> command.replyTo().tell(new ToolProtocol.ToolResult(
                        command.requestId(),
                        command.toolName(),
                        "",
                        new IllegalArgumentException("Unknown tool: " + command.toolName())
                ));
        }
        return this;
    }
}
