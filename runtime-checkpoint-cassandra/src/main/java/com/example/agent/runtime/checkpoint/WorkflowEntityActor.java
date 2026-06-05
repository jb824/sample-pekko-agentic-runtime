package com.example.agent.runtime.checkpoint;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.Effect;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior;

import java.util.Optional;

public final class WorkflowEntityActor extends EventSourcedBehavior<WorkflowEntityActor.Command, WorkflowEntityActor.Event, WorkflowEntityActor.State> {
    public static Behavior<Command> create(String workflowId) {
        return new WorkflowEntityActor(PersistenceId.of("WorkflowEntity", workflowId));
    }

    private WorkflowEntityActor(PersistenceId persistenceId) {
        super(persistenceId);
    }

    public sealed interface Command permits Initialize, RecordCheckpoint, UpdateContextManifest, CompleteWorkflow, FailWorkflow, GetState {
    }

    public record Initialize(AgentCheckpoint checkpoint, ActorRef<State> replyTo) implements Command {
    }

    public record RecordCheckpoint(AgentCheckpoint checkpoint, ActorRef<State> replyTo) implements Command {
    }

    public record UpdateContextManifest(String contextManifestRef, ActorRef<State> replyTo) implements Command {
    }

    public record CompleteWorkflow(String lastProcessedEventId, ActorRef<State> replyTo) implements Command {
    }

    public record FailWorkflow(String lastProcessedEventId, ActorRef<State> replyTo) implements Command {
    }

    public record GetState(ActorRef<State> replyTo) implements Command {
    }

    public sealed interface Event permits WorkflowInitialized, CheckpointRecorded, ContextManifestUpdated, WorkflowCompleted, WorkflowFailed {
    }

    public record WorkflowInitialized(AgentCheckpoint checkpoint) implements Event {
    }

    public record CheckpointRecorded(AgentCheckpoint checkpoint) implements Event {
    }

    public record ContextManifestUpdated(String contextManifestRef) implements Event {
    }

    public record WorkflowCompleted(String lastProcessedEventId) implements Event {
    }

    public record WorkflowFailed(String lastProcessedEventId) implements Event {
    }

    public record State(Optional<AgentCheckpoint> checkpoint) {
        public State {
            checkpoint = checkpoint == null ? Optional.empty() : checkpoint;
        }

        public static State empty() {
            return new State(Optional.empty());
        }

        public boolean initialized() {
            return checkpoint.isPresent();
        }
    }

    @Override
    public State emptyState() {
        return State.empty();
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(Initialize.class, this::onInitialize)
                .onCommand(RecordCheckpoint.class, this::onRecordCheckpoint)
                .onCommand(UpdateContextManifest.class, this::onUpdateContextManifest)
                .onCommand(CompleteWorkflow.class, this::onCompleteWorkflow)
                .onCommand(FailWorkflow.class, this::onFailWorkflow)
                .onCommand(GetState.class, (state, command) -> Effect().reply(command.replyTo(), state))
                .build();
    }

    @Override
    public EventHandler<State, Event> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(WorkflowInitialized.class, (state, event) -> new State(Optional.of(event.checkpoint())))
                .onEvent(CheckpointRecorded.class, (state, event) -> new State(Optional.of(event.checkpoint())))
                .onEvent(ContextManifestUpdated.class, this::applyContextManifestUpdated)
                .onEvent(WorkflowCompleted.class, this::applyWorkflowCompleted)
                .onEvent(WorkflowFailed.class, this::applyWorkflowFailed)
                .build();
    }

    private Effect<Event, State> onInitialize(State state, Initialize command) {
        if (state.initialized()) {
            return Effect().reply(command.replyTo(), state);
        }
        return Effect()
                .persist(new WorkflowInitialized(command.checkpoint()))
                .thenReply(command.replyTo(), updated -> updated);
    }

    private Effect<Event, State> onRecordCheckpoint(State state, RecordCheckpoint command) {
        return Effect()
                .persist(new CheckpointRecorded(command.checkpoint()))
                .thenReply(command.replyTo(), updated -> updated);
    }

    private Effect<Event, State> onUpdateContextManifest(State state, UpdateContextManifest command) {
        if (state.checkpoint().isEmpty()) {
            return Effect().reply(command.replyTo(), state);
        }
        return Effect()
                .persist(new ContextManifestUpdated(command.contextManifestRef()))
                .thenReply(command.replyTo(), updated -> updated);
    }

    private Effect<Event, State> onCompleteWorkflow(State state, CompleteWorkflow command) {
        if (state.checkpoint().isEmpty()) {
            return Effect().reply(command.replyTo(), state);
        }
        return Effect()
                .persist(new WorkflowCompleted(command.lastProcessedEventId()))
                .thenReply(command.replyTo(), updated -> updated);
    }

    private Effect<Event, State> onFailWorkflow(State state, FailWorkflow command) {
        if (state.checkpoint().isEmpty()) {
            return Effect().reply(command.replyTo(), state);
        }
        return Effect()
                .persist(new WorkflowFailed(command.lastProcessedEventId()))
                .thenReply(command.replyTo(), updated -> updated);
    }

    private State applyContextManifestUpdated(State state, ContextManifestUpdated event) {
        return state.checkpoint()
                .map(checkpoint -> new State(Optional.of(checkpoint.withContextManifestRef(event.contextManifestRef()))))
                .orElse(state);
    }

    private State applyWorkflowCompleted(State state, WorkflowCompleted event) {
        return state.checkpoint()
                .map(checkpoint -> new State(Optional.of(checkpoint
                        .withStep(WorkflowStep.COMPLETED)
                        .withLastEventSeqNr(checkpoint.lastEventSeqNr() + 1))))
                .orElse(state);
    }

    private State applyWorkflowFailed(State state, WorkflowFailed event) {
        return state.checkpoint()
                .map(checkpoint -> new State(Optional.of(checkpoint
                        .withStep(WorkflowStep.FAILED)
                        .withLastEventSeqNr(checkpoint.lastEventSeqNr() + 1))))
                .orElse(state);
    }
}
