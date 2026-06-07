package com.example.agent.api;

import com.example.agent.runtime.goal.GoalRegistryActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;

import java.time.Duration;
import java.util.Objects;

public final class AgentComponentClient {
    private final ActorRef<GoalRegistryActor.Command> goalRegistry;
    private final Scheduler scheduler;
    private final Duration defaultTimeout;

    AgentComponentClient(
            ActorRef<GoalRegistryActor.Command> goalRegistry,
            Scheduler scheduler,
            Duration defaultTimeout
    ) {
        this.goalRegistry = Objects.requireNonNull(goalRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
    }

    public GatewayAgentClient forGatewayAgent(AgentSystem system, String instanceId) {
        return new GatewayAgentClient(goalRegistry, scheduler, defaultTimeout, system, instanceId);
    }

    public GoalClient forGoal(String goalId) {
        return new GoalClient(goalRegistry, scheduler, defaultTimeout, goalId);
    }
}
