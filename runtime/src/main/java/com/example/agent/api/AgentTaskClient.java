package com.example.agent.api;

import com.example.agent.runtime.task.AgentTaskRegistryActor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class AgentTaskClient {
    private final ActorRef<AgentTaskRegistryActor.Command> taskRegistry;
    private final Scheduler scheduler;
    private final Duration defaultTimeout;
    private final String taskId;

    AgentTaskClient(
            ActorRef<AgentTaskRegistryActor.Command> taskRegistry,
            Scheduler scheduler,
            Duration defaultTimeout,
            String taskId
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
        this.taskId = Objects.requireNonNull(taskId);
    }

    public AgentTaskState get() {
        return getAsync().toCompletableFuture().join();
    }

    public CompletionStage<AgentTaskState> getAsync() {
        return AskPattern.<AgentTaskRegistryActor.Command, com.example.agent.runtime.task.AgentTaskState>ask(
                taskRegistry,
                replyTo -> new AgentTaskRegistryActor.GetTask(taskId, replyTo),
                Duration.ofSeconds(5),
                scheduler
        ).thenApply(state -> new AgentTaskState(
                state.taskId(),
                state.status().name(),
                state.result(),
                state.error()
        ));
    }
}
