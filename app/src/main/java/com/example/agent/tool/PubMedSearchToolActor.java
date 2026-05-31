package com.example.agent.tool;

import com.example.agent.tool.service.ToolService;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PubMedSearchToolActor extends AbstractBehavior<ToolProtocol.Command> {
    public static final String TOOL_NAME = "pubmed.search";

    private static final Logger LOGGER = LoggerFactory.getLogger(PubMedSearchToolActor.class);
    private final ExecutorService executor;
    private final ToolService toolService;

    public static Behavior<ToolProtocol.Command> create() {
        return Behaviors.setup(PubMedSearchToolActor::new);
    }

    public static Behavior<ToolProtocol.Command> create(ToolService toolService) {
        return Behaviors.setup(context -> new PubMedSearchToolActor(context, toolService));
    }

    private PubMedSearchToolActor(ActorContext<ToolProtocol.Command> context) {
        this(context, new DefaultToolWiring().pubMedSearchToolService());
    }

    private PubMedSearchToolActor(ActorContext<ToolProtocol.Command> context, ToolService toolService) {
        super(context);
        this.toolService = toolService;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("pubmed-tool-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<ToolProtocol.Command> onPostStop() {
        executor.shutdownNow();
        return Behaviors.same();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        getContext().getLog().info("Tool {} invoked for request {}", command.toolName(), command.requestId());
        executor.execute(() -> runSearch(command));

        return this;
    }

    private void runSearch(ToolProtocol.InvokeTool command) {
        try {
            String output = toolService.execute(command.arguments());
            ToolProtocol.ToolResult result = new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    output,
                    null
            );
            LOGGER.info(
                    "Tool {} completed for request {}: {}",
                    command.toolName(),
                    command.requestId(),
                    resourceSummary(result.output())
            );
            command.replyTo().tell(result);
        } catch (Throwable failure) {
            Throwable root = rootCause(failure);
            LOGGER.warn(
                    "Tool {} failed for request {}: {}: {}",
                    command.toolName(),
                    command.requestId(),
                    root.getClass().getSimpleName(),
                    root.getMessage()
            );
            command.replyTo().tell(failed(command, failure));
        }
    }

    private static String resourceSummary(String output) {
        List<String> urls = output.lines()
                .filter(line -> line.trim().startsWith("URL:"))
                .map(line -> line.trim().substring("URL:".length()).trim())
                .toList();
        return "resources=" + urls.size() + " urls=" + urls;
    }

    private static ToolProtocol.ToolResult failed(ToolProtocol.InvokeTool command, Throwable error) {
        return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "", error);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

}
