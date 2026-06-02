package com.example.agent.runtime.task;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.WorkflowRuntimeService;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class AgentTaskRegistryActor extends AbstractBehavior<AgentTaskRegistryActor.Command> {
    private final WorkflowRuntimeService runtimeService;
    private final Map<String, AgentTaskState> tasks = new HashMap<>();

    public static Behavior<Command> create(WorkflowRuntimeService runtimeService) {
        return Behaviors.setup(context -> new AgentTaskRegistryActor(context, runtimeService));
    }

    private AgentTaskRegistryActor(ActorContext<Command> context, WorkflowRuntimeService runtimeService) {
        super(context);
        this.runtimeService = Objects.requireNonNull(runtimeService);
    }

    public sealed interface Command permits StartTask, GetTask, WrappedTaskResult {
    }

    public record StartTask(
            String taskId,
            String input,
            Duration timeout,
            AgentSystemDefinition system,
            ActorRef<AgentTaskState> replyTo
    ) implements Command {
    }

    public record GetTask(String taskId, ActorRef<AgentTaskState> replyTo) implements Command {
    }

    private record WrappedTaskResult(String taskId, AgentResult result, Throwable failure) implements Command {
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartTask.class, this::onStartTask)
                .onMessage(GetTask.class, this::onGetTask)
                .onMessage(WrappedTaskResult.class, this::onWrappedTaskResult)
                .build();
    }

    private Behavior<Command> onStartTask(StartTask command) {
        tasks.put(command.taskId(), AgentTaskState.running(command.taskId()));
        command.replyTo().tell(tasks.get(command.taskId()));
        runtimeService.invoke(
                new AgentRequest(command.taskId(), command.input()),
                command.system(),
                command.timeout()
        ).whenComplete((result, failure) ->
                getContext().getSelf().tell(new WrappedTaskResult(command.taskId(), result, failure)));
        return this;
    }

    private Behavior<Command> onGetTask(GetTask command) {
        command.replyTo().tell(tasks.getOrDefault(command.taskId(), AgentTaskState.notFound(command.taskId())));
        return this;
    }

    private Behavior<Command> onWrappedTaskResult(WrappedTaskResult wrapped) {
        if (wrapped.failure() != null) {
            tasks.put(wrapped.taskId(), AgentTaskState.failed(wrapped.taskId(), wrapped.failure().getMessage()));
        } else if (wrapped.result() == null) {
            tasks.put(wrapped.taskId(), AgentTaskState.failed(wrapped.taskId(), "Task completed without a result."));
        } else {
            tasks.put(wrapped.taskId(), AgentTaskState.completed(wrapped.taskId(), wrapped.result()));
        }
        return this;
    }
}
