package com.example.agent.rag.runtime;

import com.example.agent.rag.core.IndexDocumentRequest;
import com.example.agent.rag.core.RagIndexResult;
import com.example.agent.rag.core.RagRetrievalResult;
import com.example.agent.rag.core.RagSecurityContext;
import org.apache.pekko.actor.typed.ActorRef;

public final class RagProtocol {
    private RagProtocol() {
    }

    public sealed interface Command permits Retrieve, IndexDocument, ReindexDocument, DeleteDocument {
    }

    public record Retrieve(String requestId, String query, RagSecurityContext securityContext, int topK, ActorRef<Retrieved> replyTo) implements Command {
    }

    public record IndexDocument(String requestId, IndexDocumentRequest document, ActorRef<IndexCompleted> replyTo) implements Command {
    }

    public record ReindexDocument(String requestId, IndexDocumentRequest document, ActorRef<IndexCompleted> replyTo) implements Command {
    }

    public record DeleteDocument(String requestId, String tenantId, String documentId, ActorRef<DeleteCompleted> replyTo) implements Command {
    }

    public record Retrieved(String requestId, RagRetrievalResult result, Throwable failure) {
        public boolean isSuccess() {
            return failure == null;
        }
    }

    public record IndexCompleted(String requestId, RagIndexResult result, Throwable failure) {
        public boolean isSuccess() {
            return failure == null;
        }
    }

    public record DeleteCompleted(String requestId, Throwable failure) {
        public boolean isSuccess() {
            return failure == null;
        }
    }
}
