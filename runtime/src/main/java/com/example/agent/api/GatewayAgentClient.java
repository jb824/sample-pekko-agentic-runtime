package com.example.agent.api;

import com.example.agent.runtime.agent.AgentSystemDefinition;
import com.example.agent.runtime.task.AgentTaskState;
import com.example.agent.runtime.task.AgentTaskRegistryActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class GatewayAgentClient {
    private final ActorRef<AgentTaskRegistryActor.Command> taskRegistry;
    private final Scheduler scheduler;
    private final Duration defaultTimeout;
    private final AgentSystem system;
    private final String instanceId;

    GatewayAgentClient(
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            Scheduler scheduler,
            Duration defaultTimeout,
            AgentSystem system,
            String instanceId
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
        this.system = Objects.requireNonNull(system);
        this.instanceId = Objects.requireNonNull(instanceId);
    }

    public String runSingleTask(AgentTask task) {
        return runSingleTaskAsync(task).toCompletableFuture().join();
    }

    public CompletionStage<String> runSingleTaskAsync(AgentTask task) {
        AgentSystemDefinition runtimeSystem = AgentSystemMapper.toRuntime(system);
        return AskPattern.<AgentTaskRegistryActor.Command, AgentTaskState>ask(
                taskRegistry,
                replyTo -> new AgentTaskRegistryActor.StartTask(
                        instanceId,
                        task.instructions(),
                        defaultTimeout,
                        runtimeSystem,
                        replyTo
                ),
                Duration.ofSeconds(5),
                scheduler
        ).thenApply(state -> state.taskId());
    }
}
