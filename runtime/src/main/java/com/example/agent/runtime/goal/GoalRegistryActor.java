package com.example.agent.runtime.goal;

import com.example.agent.api.AgentSystem;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.protocol.AgentResult;
import com.example.agent.runtime.AgentRuntimeInvoker;
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

public final class GoalRegistryActor extends AbstractBehavior<GoalRegistryActor.Command> {
    private final AgentRuntimeInvoker runtimeInvoker;
    private final Duration taskRetention;
    private final int maxRetainedTasks;
    private final Clock clock;
    private final Map<String, StoredGoal> goals = new HashMap<>();

    public static Behavior<Command> create(
            AgentRuntimeInvoker runtimeInvoker,
            Duration taskRetention,
            int maxRetainedTasks
    ) {
        return Behaviors.setup(context -> new GoalRegistryActor(
                context,
                runtimeInvoker,
                taskRetention,
                maxRetainedTasks,
                Clock.systemUTC()
        ));
    }

    static Behavior<Command> create(
            AgentRuntimeInvoker runtimeInvoker,
            Duration taskRetention,
            int maxRetainedTasks,
            Clock clock
    ) {
        return Behaviors.setup(context -> new GoalRegistryActor(
                context,
                runtimeInvoker,
                taskRetention,
                maxRetainedTasks,
                clock
        ));
    }

    private GoalRegistryActor(
            ActorContext<Command> context,
            AgentRuntimeInvoker runtimeInvoker,
            Duration taskRetention,
            int maxRetainedTasks,
            Clock clock
    ) {
        super(context);
        this.runtimeInvoker = Objects.requireNonNull(runtimeInvoker);
        this.taskRetention = Objects.requireNonNull(taskRetention);
        this.maxRetainedTasks = Math.max(1, maxRetainedTasks);
        this.clock = Objects.requireNonNull(clock);
        scheduleCleanup();
    }

    public sealed interface Command permits StartGoal, GetGoal, WrappedGoalResult, CleanupExpiredGoals {
    }

    public record StartGoal(
            String goalId,
            String input,
            Duration timeout,
            AgentSystem system,
            ActorRef<GoalState> replyTo
    ) implements Command {
    }

    public record GetGoal(String goalId, ActorRef<GoalState> replyTo) implements Command {
    }

    private record WrappedGoalResult(String goalId, AgentResult result, Throwable failure) implements Command {
    }

    private record CleanupExpiredGoals() implements Command {
    }

    private record StoredGoal(GoalState state, long terminalAtMillis) {
        boolean isTerminal() {
            return state.status() == GoalStatus.COMPLETED || state.status() == GoalStatus.FAILED;
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartGoal.class, this::onStartGoal)
                .onMessage(GetGoal.class, this::onGetGoal)
                .onMessage(WrappedGoalResult.class, this::onWrappedGoalResult)
                .onMessage(CleanupExpiredGoals.class, this::onCleanupExpiredGoals)
                .build();
    }

    private Behavior<Command> onStartGoal(StartGoal command) {
        pruneTasks();
        GoalState running = GoalState.running(command.goalId());
        goals.put(command.goalId(), new StoredGoal(running, 0L));
        command.replyTo().tell(running);
        runtimeInvoker.invoke(
                new AgentRequest(command.goalId(), command.input()),
                command.system(),
                command.timeout()
        ).whenComplete((result, failure) ->
                getContext().getSelf().tell(new WrappedGoalResult(command.goalId(), result, failure)));
        return this;
    }

    private Behavior<Command> onGetGoal(GetGoal command) {
        pruneTasks();
        StoredGoal goal = goals.get(command.goalId());
        command.replyTo().tell(goal == null ? GoalState.notFound(command.goalId()) : goal.state());
        return this;
    }

    private Behavior<Command> onWrappedGoalResult(WrappedGoalResult wrapped) {
        pruneTasks();
        if (wrapped.failure() != null) {
            goals.put(
                    wrapped.goalId(),
                    new StoredGoal(GoalState.failed(wrapped.goalId(), wrapped.failure().getMessage()), clock.millis())
            );
        } else if (wrapped.result() == null) {
            goals.put(
                    wrapped.goalId(),
                    new StoredGoal(GoalState.failed(wrapped.goalId(), "Goal completed without a result."), clock.millis())
            );
        } else {
            goals.put(
                    wrapped.goalId(),
                    new StoredGoal(GoalState.completed(wrapped.goalId(), wrapped.result()), clock.millis())
            );
        }
        enforceRetentionLimit();
        return this;
    }

    private Behavior<Command> onCleanupExpiredGoals(CleanupExpiredGoals ignored) {
        pruneTasks();
        scheduleCleanup();
        return this;
    }

    private void pruneTasks() {
        long now = clock.millis();
        if (!taskRetention.isZero() && !taskRetention.isNegative()) {
            long cutoff = now - taskRetention.toMillis();
            goals.entrySet().removeIf(entry -> entry.getValue().isTerminal() && entry.getValue().terminalAtMillis() <= cutoff);
        }
        enforceRetentionLimit();
    }

    private void enforceRetentionLimit() {
        List<Map.Entry<String, StoredGoal>> terminalGoals = new ArrayList<>();
        for (Map.Entry<String, StoredGoal> entry : goals.entrySet()) {
            if (entry.getValue().isTerminal()) {
                terminalGoals.add(entry);
            }
        }
        if (terminalGoals.size() <= maxRetainedTasks) {
            return;
        }
        terminalGoals.sort(Comparator.comparingLong(entry -> entry.getValue().terminalAtMillis()));
        int toRemove = terminalGoals.size() - maxRetainedTasks;
        for (int index = 0; index < toRemove; index++) {
            goals.remove(terminalGoals.get(index).getKey());
        }
    }

    private void scheduleCleanup() {
        Duration cleanupInterval = taskRetention.isZero() || taskRetention.isNegative()
                ? Duration.ofMinutes(5)
                : taskRetention;
        getContext().scheduleOnce(cleanupInterval, getContext().getSelf(), new CleanupExpiredGoals());
    }
}
