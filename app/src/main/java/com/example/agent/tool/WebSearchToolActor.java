package com.example.agent.tool;

import com.example.agent.tool.service.ToolService;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

public final class WebSearchToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "web.search";

    private final ToolService toolService;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(WebSearchToolActor::new);
    }

    public static Behavior<ToolProtocol.Command> create(ToolService toolService) {
        return Behaviors.setup(context -> new WebSearchToolActor(context, toolService));
    }

    private WebSearchToolActor(ActorContext<ToolProtocol.Command> context) {
        this(context, new DefaultToolWiring().webSearchToolService());
    }

    private WebSearchToolActor(ActorContext<ToolProtocol.Command> context, ToolService toolService) {
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
        getContext().getLog().info("Tool {} invoked for request {}", command.toolName(), command.requestId());
        CompletableFuture.supplyAsync(() -> {
                    try {
                        String output = toolService.execute(command.arguments());
                        return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), output, null);
                    } catch (Exception exception) {
                        return failed(command, exception);
                    }
                })
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        command.replyTo().tell(failed(command, failure));
                    } else {
                        getContext().getLog().info(
                                "Tool {} completed for request {}: {}",
                                command.toolName(),
                                command.requestId(),
                                resourceSummary(response.output())
                        );
                        command.replyTo().tell(response);
                    }
                });

        return this;
    }

    private static String resourceSummary(String output) {
        List<String> urls = output.lines()
                .filter(line -> line.trim().startsWith("URL:"))
                .map(line -> line.trim().substring("URL:".length()).trim())
                .filter(url -> !url.equals("<unknown>"))
                .toList();
        return "resources=" + urls.size() + " urls=" + urls;
    }

    private static ToolProtocol.ToolResult failed(ToolProtocol.InvokeTool command, Throwable error) {
        return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "", error);
    }
}
