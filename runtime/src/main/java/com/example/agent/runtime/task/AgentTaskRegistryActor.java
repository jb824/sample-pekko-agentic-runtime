package com.example.agent.runtime.task;

import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.AgentRuntimeService;
import com.example.agent.runtime.AgentResult;
import com.example.agent.runtime.agent.AgentSystemDefinition;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentTaskRegistryActor extends AbstractBehavior<AgentTaskRegistryActor.Command> {
    private final AgentRuntimeService runtimeService;
    private final Duration taskRetention;
    private final int maxRetainedTasks;
    private final Clock clock;
    private final Map<String, StoredTask> tasks = new HashMap<>();

    public static Behavior<Command> create(
            AgentRuntimeService runtimeService,
            Duration taskRetention,
            int maxRetainedTasks
    ) {
        return Behaviors.setup(context -> new AgentTaskRegistryActor(
                context,
                runtimeService,
                taskRetention,
                maxRetainedTasks,
                Clock.systemUTC()
        ));
    }

    static Behavior<Command> create(
            AgentRuntimeService runtimeService,
            Duration taskRetention,
            int maxRetainedTasks,
            Clock clock
    ) {
        return Behaviors.setup(context -> new AgentTaskRegistryActor(
                context,
                runtimeService,
                taskRetention,
                maxRetainedTasks,
                clock
        ));
    }

    private AgentTaskRegistryActor(
            ActorContext<Command> context,
            AgentRuntimeService runtimeService,
            Duration taskRetention,
            int maxRetainedTasks,
            Clock clock
    ) {
        super(context);
        this.runtimeService = Objects.requireNonNull(runtimeService);
        this.taskRetention = Objects.requireNonNull(taskRetention);
        this.maxRetainedTasks = Math.max(1, maxRetainedTasks);
        this.clock = Objects.requireNonNull(clock);
        scheduleCleanup();
    }

    public sealed interface Command permits StartTask, GetTask, WrappedTaskResult, CleanupExpiredTasks {
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

    private record CleanupExpiredTasks() implements Command {
    }

    private record StoredTask(AgentTaskState state, long terminalAtMillis) {
        boolean isTerminal() {
            return state.status() == AgentTaskStatus.COMPLETED || state.status() == AgentTaskStatus.FAILED;
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartTask.class, this::onStartTask)
                .onMessage(GetTask.class, this::onGetTask)
                .onMessage(WrappedTaskResult.class, this::onWrappedTaskResult)
                .onMessage(CleanupExpiredTasks.class, this::onCleanupExpiredTasks)
                .build();
    }

    private Behavior<Command> onStartTask(StartTask command) {
        pruneTasks();
        AgentTaskState running = AgentTaskState.running(command.taskId());
        tasks.put(command.taskId(), new StoredTask(running, 0L));
        command.replyTo().tell(running);
        runtimeService.invoke(
                new AgentRequest(command.taskId(), command.input()),
                command.system(),
                command.timeout()
        ).whenComplete((result, failure) ->
                getContext().getSelf().tell(new WrappedTaskResult(command.taskId(), result, failure)));
        return this;
    }

    private Behavior<Command> onGetTask(GetTask command) {
        pruneTasks();
        StoredTask task = tasks.get(command.taskId());
        command.replyTo().tell(task == null ? AgentTaskState.notFound(command.taskId()) : task.state());
        return this;
    }

    private Behavior<Command> onWrappedTaskResult(WrappedTaskResult wrapped) {
        pruneTasks();
        if (wrapped.failure() != null) {
            tasks.put(
                    wrapped.taskId(),
                    new StoredTask(AgentTaskState.failed(wrapped.taskId(), wrapped.failure().getMessage()), clock.millis())
            );
        } else if (wrapped.result() == null) {
            tasks.put(
                    wrapped.taskId(),
                    new StoredTask(AgentTaskState.failed(wrapped.taskId(), "Task completed without a result."), clock.millis())
            );
        } else {
            tasks.put(
                    wrapped.taskId(),
                    new StoredTask(AgentTaskState.completed(wrapped.taskId(), wrapped.result()), clock.millis())
            );
        }
        enforceRetentionLimit();
        return this;
    }

    private Behavior<Command> onCleanupExpiredTasks(CleanupExpiredTasks ignored) {
        pruneTasks();
        scheduleCleanup();
        return this;
    }

    private void pruneTasks() {
        long now = clock.millis();
        if (!taskRetention.isZero() && !taskRetention.isNegative()) {
            long cutoff = now - taskRetention.toMillis();
            tasks.entrySet().removeIf(entry -> entry.getValue().isTerminal() && entry.getValue().terminalAtMillis() <= cutoff);
        }
        enforceRetentionLimit();
    }

    private void enforceRetentionLimit() {
        List<Map.Entry<String, StoredTask>> terminalTasks = new ArrayList<>();
        for (Map.Entry<String, StoredTask> entry : tasks.entrySet()) {
            if (entry.getValue().isTerminal()) {
                terminalTasks.add(entry);
            }
        }
        if (terminalTasks.size() <= maxRetainedTasks) {
            return;
        }
        terminalTasks.sort(Comparator.comparingLong(entry -> entry.getValue().terminalAtMillis()));
        int toRemove = terminalTasks.size() - maxRetainedTasks;
        for (int index = 0; index < toRemove; index++) {
            tasks.remove(terminalTasks.get(index).getKey());
        }
    }

    private void scheduleCleanup() {
        Duration cleanupInterval = taskRetention.isZero() || taskRetention.isNegative()
                ? Duration.ofMinutes(5)
                : taskRetention;
        getContext().scheduleOnce(cleanupInterval, getContext().getSelf(), new CleanupExpiredTasks());
    }
}
