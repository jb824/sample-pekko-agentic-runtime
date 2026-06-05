package com.example.agent.runtime.tool;

import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolRequest;
import com.example.agent.api.AgentToolResult;
import com.example.agent.config.PekkoRuntimeConfig;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class ToolRegistryActor extends AbstractBehavior<ToolProtocol.Command> {
    private final Map<String, AgentToolDefinition> definitions;
    private final Executor toolExecutor;
    private final int maxConcurrent;
    private final int maxQueued;
    private final Deque<ToolProtocol.InvokeTool> pending = new ArrayDeque<>();
    private int inFlight;

    public static Behavior<ToolProtocol.Command> create(List<AgentToolDefinition> definitions, int maxConcurrent, int maxQueued) {
        return Behaviors.setup(context -> new ToolRegistryActor(
                context,
                definitions,
                context.getSystem().dispatchers().lookup(DispatcherSelector.fromConfig(PekkoRuntimeConfig.TOOL_DISPATCHER_PATH)),
                maxConcurrent,
                maxQueued
        ));
    }

    private ToolRegistryActor(
            ActorContext<ToolProtocol.Command> context,
            List<AgentToolDefinition> definitions,
            Executor toolExecutor,
            int maxConcurrent,
            int maxQueued
    ) {
        super(context);
        this.definitions = index(definitions);
        this.toolExecutor = Objects.requireNonNull(toolExecutor);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxQueued = Math.max(0, maxQueued);
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.RegisterTools.class, this::onRegisterTools)
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .onMessage(ToolProtocol.WrappedResult.class, this::onWrappedResult)
                .build();
    }

    private Behavior<ToolProtocol.Command> onRegisterTools(ToolProtocol.RegisterTools command) {
        int registered = 0;
        int duplicates = 0;
        for (AgentToolDefinition definition : command.toolDefinitions()) {
            if (definition == null) {
                continue;
            }
            if (definitions.containsKey(definition.name())) {
                duplicates++;
                continue;
            }
            definitions.put(definition.name(), definition);
            registered++;
        }
        getContext().getLog().info(
                "Agent tools registered request_id={} tenant={} registered={} duplicates={} total={}",
                command.requestId(),
                command.tenantId(),
                registered,
                duplicates,
                definitions.size()
        );
        command.replyTo().tell(new ToolProtocol.ToolsRegistered(command.requestId(), registered, duplicates));
        return this;
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        Objects.requireNonNull(command.toolName());
        AgentToolDefinition definition = definitions.get(command.toolName());
        if (definition == null) {
            getContext().getLog().warn(
                    "Agent tool rejected request_id={} tenant={} agent={} tool={} reason=unregistered",
                    command.requestId(),
                    command.tenantId(),
                    agentLoggerName(command.agentName()),
                    command.toolName()
            );
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.agentName(),
                    command.toolName(),
                    "",
                    List.of(),
                    new IllegalArgumentException("Unregistered tool: " + command.toolName())
            ));
            return this;
        }
        if (inFlight < maxConcurrent) {
            dispatch(command, definition);
            return this;
        }
        if (pending.size() < maxQueued) {
            pending.addLast(command);
            return this;
        }
        command.replyTo().tell(new ToolProtocol.ToolResult(
                command.requestId(),
                command.agentName(),
                command.toolName(),
                "",
                List.of(),
                new RejectedExecutionException(
                        "Tool registry saturated: in_flight=" + inFlight + " queued=" + pending.size()
                                + " max_concurrent=" + maxConcurrent + " max_queued=" + maxQueued
                )
        ));
        return this;
    }

    private void dispatch(ToolProtocol.InvokeTool command, AgentToolDefinition definition) {
        inFlight++;
        getContext().getLog().info(
                "Agent tool invoked request_id={} tenant={} agent={} tool={} source_capable={}",
                command.requestId(),
                command.tenantId(),
                agentLoggerName(command.agentName()),
                command.toolName(),
                definition.sourceCapable()
        );
        AgentToolRequest request = new AgentToolRequest(
                command.requestId(),
                command.tenantId(),
                command.toolName(),
                command.userInput(),
                command.arguments()
        );
        CompletionStage<AgentToolResult> future;
        try {
            future = CompletableFuture
                    .supplyAsync(() -> definition.handler().invoke(request), toolExecutor)
                    .thenCompose(stage -> stage == null ? CompletableFuture.completedFuture(AgentToolResult.success("")) : stage);
        } catch (RejectedExecutionException exception) {
            inFlight = Math.max(0, inFlight - 1);
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.agentName(),
                    command.toolName(),
                    "",
                    List.of(),
                    exception
            ));
            drainQueue();
            return;
        }
        getContext().pipeToSelf(future, (result, failure) -> new ToolProtocol.WrappedResult(command, result, failure));
    }

    private Behavior<ToolProtocol.Command> onWrappedResult(ToolProtocol.WrappedResult wrapped) {
        inFlight = Math.max(0, inFlight - 1);
        ToolProtocol.InvokeTool command = wrapped.command();
        if (wrapped.failure() != null) {
            getContext().getLog().warn(
                    "Agent tool failed request_id={} tenant={} agent={} tool={} error={}",
                    command.requestId(),
                    command.tenantId(),
                    agentLoggerName(command.agentName()),
                    command.toolName(),
                    wrapped.failure().toString()
            );
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.agentName(),
                    command.toolName(),
                    "",
                    List.of(),
                    wrapped.failure()
            ));
            drainQueue();
            return this;
        }
        AgentToolResult resolved = wrapped.result() == null ? AgentToolResult.success("") : wrapped.result();
        if (resolved.isSuccess()) {
            getContext().getLog().info(
                    "Agent tool completed request_id={} tenant={} agent={} tool={} output_chars={} sources={}",
                    command.requestId(),
                    command.tenantId(),
                    agentLoggerName(command.agentName()),
                    command.toolName(),
                    resolved.output().length(),
                    resolved.sources().size()
            );
        } else {
            getContext().getLog().warn(
                    "Agent tool failed request_id={} tenant={} agent={} tool={} error={}",
                    command.requestId(),
                    command.tenantId(),
                    agentLoggerName(command.agentName()),
                    command.toolName(),
                    resolved.error().toString()
            );
        }
        command.replyTo().tell(new ToolProtocol.ToolResult(
                command.requestId(),
                command.agentName(),
                command.toolName(),
                resolved.output(),
                resolved.sources(),
                resolved.error()
        ));
        drainQueue();
        return this;
    }

    private void drainQueue() {
        while (inFlight < maxConcurrent && !pending.isEmpty()) {
            ToolProtocol.InvokeTool command = pending.removeFirst();
            AgentToolDefinition definition = definitions.get(command.toolName());
            if (definition == null) {
                command.replyTo().tell(new ToolProtocol.ToolResult(
                        command.requestId(),
                        command.agentName(),
                        command.toolName(),
                        "",
                        List.of(),
                        new IllegalArgumentException("Unregistered tool: " + command.toolName())
                ));
                continue;
            }
            dispatch(command, definition);
        }
    }

    private static String agentLoggerName(String agentName) {
        return agentName == null || agentName.isBlank() ? "agent.<unknown>" : "agent." + agentName;
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
        return indexed;
    }
}
