package com.example.agent.runtime.agent;

import com.example.agent.rag.core.RagContextBuilder;
import com.example.agent.rag.core.RagRetrievalResult;
import com.example.agent.rag.core.RagSecurityContext;
import com.example.agent.rag.runtime.RagProtocol;
import org.apache.pekko.actor.typed.ActorRef;

import java.util.List;
import java.util.Map;

final class GlobalRagStage {
    private final boolean enabled;
    private final int topK;
    private final RagContextBuilder contextBuilder;

    GlobalRagStage(boolean enabled, int topK, int maxContextChars) {
        this.enabled = enabled;
        this.topK = Math.max(1, topK);
        this.contextBuilder = new RagContextBuilder(maxContextChars);
    }

    boolean enabled() {
        return enabled;
    }

    int topK() {
        return topK;
    }

    RagProtocol.Retrieve retrieve(DefaultAgentRunState state, ActorRef<RagProtocol.Retrieved> replyTo) {
        return new RagProtocol.Retrieve(
                state.request().requestId() + ":rag",
                state.request().input(),
                new RagSecurityContext(state.request().tenantId(), "", Map.of()),
                topK,
                replyTo);
    }

    DefaultAgentRunState applyRetrieved(DefaultAgentRunState state, RagProtocol.Retrieved retrieved) {
        if (!retrieved.isSuccess() || retrieved.result() == null) {
            return state;
        }
        RagRetrievalResult result = retrieved.result();
        String context = contextBuilder.build(result);
        List<String> citations = result.chunks().stream()
                .map(chunk -> chunk.citation())
                .filter(citation -> citation != null && !citation.isBlank())
                .toList();
        return state.withRag(context, citations);
    }
}
