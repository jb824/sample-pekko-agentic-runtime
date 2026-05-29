package com.example.agent.llm;

import org.apache.pekko.actor.typed.ActorRef;

public final class LlmProtocol {
    private LlmProtocol() {
    }

    public sealed interface Command permits Ask, WrappedResult {
    }

    public record Ask(
            String requestId,
            String prompt,
            ActorRef<Response> replyTo
    ) implements Command {
    }

    public record Response(
            String requestId,
            String text,
            Throwable error
    ) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    record WrappedResult(
            String requestId,
            ActorRef<Response> replyTo,
            String text,
            Throwable error
    ) implements Command {
    }
}
