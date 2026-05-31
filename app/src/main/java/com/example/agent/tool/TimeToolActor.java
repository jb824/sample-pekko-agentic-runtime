package com.example.agent.tool;

import com.example.agent.tool.service.ToolService;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

public final class TimeToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "time.now";

    private final ToolService toolService;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(context -> new TimeToolActor(context));
    }

    public static Behavior<ToolProtocol.Command> create(ToolService toolService) {
        return Behaviors.setup(context -> new TimeToolActor(context, toolService));
    }

    private TimeToolActor(ActorContext<ToolProtocol.Command> context) {
        this(context, new DefaultToolWiring().timeToolService());
    }

    private TimeToolActor(ActorContext<ToolProtocol.Command> context, ToolService toolService) {
        super(context);
        this.toolService = toolService;
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        try {
            String output = toolService.execute(command.arguments());
            getContext().getLog().info("Tool {} invoked for request {}", command.toolName(), command.requestId());
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    output,
                    null
            ));
        } catch (Exception exception) {
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    "",
                    exception
            ));
        }
        return this;
    }
}
