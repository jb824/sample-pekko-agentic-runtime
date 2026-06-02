package com.example.agent.runtime;

import com.example.agent.tool.ToolCatalog;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PolicyActor extends AbstractBehavior<PolicyActor.Command> {
    public sealed interface Command permits EvaluateAction {
    }

    public record EvaluateAction(
            String toolName,
            String query,
            List<String> enabledTools,
            int toolCalls,
            int maxTools,
            ActorRef<PolicyDecision> replyTo
    ) implements Command {
    }

    public record PolicyDecision(boolean allowed, String reason) {
    }

    private final Set<String> duplicateGuard = new HashSet<>();

    public static Behavior<Command> create() {
        return Behaviors.setup(PolicyActor::new);
    }

    private PolicyActor(ActorContext<Command> context) {
        super(context);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(EvaluateAction.class, this::onEvaluateAction)
                .build();
    }

    private Behavior<Command> onEvaluateAction(EvaluateAction command) {
        Objects.requireNonNull(command.toolName());
        if (!command.enabledTools().contains(command.toolName())) {
            command.replyTo().tell(new PolicyDecision(false, "tool_not_enabled"));
            return this;
        }
        if (command.toolCalls() >= command.maxTools()) {
            command.replyTo().tell(new PolicyDecision(false, "tool_budget_exhausted"));
            return this;
        }
        String duplicateKey = command.toolName() + "::" + command.query().strip().toLowerCase();
        if (!duplicateGuard.add(duplicateKey)) {
            command.replyTo().tell(new PolicyDecision(false, "duplicate_tool_call"));
            return this;
        }
        if (ToolCatalog.TIME_NOW.equals(command.toolName())
                && !looksLikeTimeQuestion(command.query())) {
            command.replyTo().tell(new PolicyDecision(false, "irrelevant_time_tool"));
            return this;
        }
        command.replyTo().tell(new PolicyDecision(true, ""));
        return this;
    }

    private static boolean looksLikeTimeQuestion(String query) {
        String normalized = query == null ? "" : query.toLowerCase();
        return normalized.contains("time")
                || normalized.contains("date")
                || normalized.contains("today")
                || normalized.contains("now");
    }
}
