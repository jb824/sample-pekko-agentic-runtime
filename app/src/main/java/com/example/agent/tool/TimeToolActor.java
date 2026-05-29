package com.example.agent.tool;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "time.now";

    private final Clock clock;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(context -> new TimeToolActor(context, Clock.systemUTC()));
    }

    private TimeToolActor(ActorContext<ToolProtocol.Command> context, Clock clock) {
        super(context);
        this.clock = clock;
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        String zone = command.arguments().getOrDefault("zone", "UTC");
        try {
            ZoneId zoneId = ZoneId.of(zone);
            Instant now = clock.instant();
            String output = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zoneId));
            getContext().getLog().info("Tool {} invoked for request {}", command.toolName(), command.requestId());
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    output,
                    null
            ));
        } catch (RuntimeException exception) {
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
