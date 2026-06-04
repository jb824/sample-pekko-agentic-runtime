package com.example.agent.rag.runtime;

import com.example.agent.rag.core.RagIndexer;
import com.example.agent.rag.core.RagRetriever;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;

public final class RagRuntimeActor extends AbstractBehavior<RagProtocol.Command> {
    private final RagRetriever retriever;
    private final RagIndexer indexer;

    public static Behavior<RagProtocol.Command> create(RagRetriever retriever, RagIndexer indexer) {
        return Behaviors.setup(context -> new RagRuntimeActor(context, retriever, indexer));
    }

    private RagRuntimeActor(ActorContext<RagProtocol.Command> context, RagRetriever retriever, RagIndexer indexer) {
        super(context);
        this.retriever = Objects.requireNonNull(retriever);
        this.indexer = Objects.requireNonNull(indexer);
    }

    @Override
    public Receive<RagProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(RagProtocol.Retrieve.class, this::onRetrieve)
                .onMessage(RagProtocol.IndexDocument.class, this::onIndexDocument)
                .onMessage(RagProtocol.ReindexDocument.class, this::onReindexDocument)
                .onMessage(RagProtocol.DeleteDocument.class, this::onDeleteDocument)
                .build();
    }

    private Behavior<RagProtocol.Command> onRetrieve(RagProtocol.Retrieve command) {
        retriever.retrieve(command.query(), command.securityContext(), command.topK())
                .whenComplete((result, failure) -> command.replyTo().tell(new RagProtocol.Retrieved(command.requestId(), result, failure)));
        return this;
    }

    private Behavior<RagProtocol.Command> onIndexDocument(RagProtocol.IndexDocument command) {
        indexer.index(command.document())
                .whenComplete((result, failure) -> command.replyTo().tell(new RagProtocol.IndexCompleted(command.requestId(), result, failure)));
        return this;
    }

    private Behavior<RagProtocol.Command> onReindexDocument(RagProtocol.ReindexDocument command) {
        indexer.reindex(command.document())
                .whenComplete((result, failure) -> command.replyTo().tell(new RagProtocol.IndexCompleted(command.requestId(), result, failure)));
        return this;
    }

    private Behavior<RagProtocol.Command> onDeleteDocument(RagProtocol.DeleteDocument command) {
        indexer.delete(command.tenantId(), command.documentId())
                .whenComplete((ignored, failure) -> command.replyTo().tell(new RagProtocol.DeleteCompleted(command.requestId(), failure)));
        return this;
    }
}
