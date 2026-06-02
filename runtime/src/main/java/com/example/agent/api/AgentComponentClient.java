package com.example.agent.api;

import com.example.agent.runtime.task.AgentTaskRegistryActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;

import java.time.Duration;
import java.util.Objects;

public final class AgentComponentClient {
    private final ActorRef<AgentTaskRegistryActor.Command> taskRegistry;
    private final Scheduler scheduler;
    private final Duration defaultTimeout;

    AgentComponentClient(
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            Scheduler scheduler,
            Duration defaultTimeout
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
    }

    public GatewayAgentClient forGatewayAgent(AgentSystem system, String instanceId) {
        return new GatewayAgentClient(taskRegistry, scheduler, defaultTimeout, system, instanceId);
    }

    public AgentTaskClient forTask(String taskId) {
        return new AgentTaskClient(taskRegistry, scheduler, defaultTimeout, taskId);
    }
}
