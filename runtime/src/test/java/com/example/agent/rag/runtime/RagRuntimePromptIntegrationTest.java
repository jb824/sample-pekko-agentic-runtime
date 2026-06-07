package com.example.agent.rag.runtime;

import com.example.agent.api.Agent;
import com.example.agent.api.AgentRunContext;
import com.example.agent.api.AgentRuntime;
import com.example.agent.api.AgentSystem;
import com.example.agent.api.GoalRequest;
import com.example.agent.api.GatewayAgent;
import com.example.agent.api.Goal;
import com.example.agent.rag.core.IndexDocumentRequest;
import com.example.agent.rag.core.RagChunkMetadata;
import com.example.agent.rag.core.RagIndexResult;
import com.example.agent.rag.core.RagIndexer;
import com.example.agent.rag.core.RagRetrievalResult;
import com.example.agent.rag.core.RagRetriever;
import com.example.agent.rag.core.RagSecurityContext;
import com.example.agent.rag.core.RetrievedChunk;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RagRuntimePromptIntegrationTest {
    @Test
    void retrievalIsCalledOnlyWhenEnabled() {
        CapturingModel model = new CapturingModel();
        CountingRetriever retriever = new CountingRetriever(RagRetrievalResult.empty());

        try (AgentRuntime runtime = runtime(model, retriever, false)) {
            runtime.run(AgentRunContext.tenant("tenant-a"), "request-1", system(), task("hello"), Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .join();
        }

        assertEquals(0, retriever.calls());
    }

    @Test
    void promptIncludesRetrievedContextAndCitationsWhenEnabled() {
        CapturingModel model = new CapturingModel();
        CountingRetriever retriever = new CountingRetriever(new RagRetrievalResult(List.of(chunk("tenant-a", "doc-1", "file://doc-1"))));

        var result = run(model, retriever, true, "tenant-a", "request-1", "question");

        assertEquals(1, retriever.calls());
        assertEquals("tenant-a", retriever.lastTenant());
        String delegatePrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("You are agent \"assistant\""))
                .findFirst()
                .orElseThrow();
        assertTrue(delegatePrompt.contains("- Retrieved knowledge:"));
        assertTrue(delegatePrompt.contains("retrieved fact"));
        assertTrue(result.sources().contains("file://doc-1"));
    }

    @Test
    void promptExcludesContextWhenRetrieverReturnsNoChunks() {
        CapturingModel model = new CapturingModel();
        CountingRetriever retriever = new CountingRetriever(RagRetrievalResult.empty());

        run(model, retriever, true, "tenant-a", "request-1", "question");

        String delegatePrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("You are agent \"assistant\""))
                .findFirst()
                .orElseThrow();
        assertTrue(delegatePrompt.contains("- Retrieved knowledge:\nNone."));
    }

    @Test
    void retrievalFailureDegradesWithoutFailingAgentRun() {
        CapturingModel model = new CapturingModel();
        CountingRetriever retriever = new CountingRetriever(new IllegalStateException("boom"));

        var result = run(model, retriever, true, "tenant-a", "request-1", "question");

        assertTrue(result.isSuccess());
        assertFalse(result.output().isBlank());
        String delegatePrompt = model.prompts().stream()
                .filter(prompt -> prompt.contains("You are agent \"assistant\""))
                .findFirst()
                .orElseThrow();
        assertTrue(delegatePrompt.contains("- Retrieved knowledge:\nNone."));
    }

    private static com.example.agent.runtime.AgentResult run(
            CapturingModel model,
            CountingRetriever retriever,
            boolean ragEnabled,
            String tenantId,
            String requestId,
            String input
    ) {
        try (AgentRuntime runtime = runtime(model, retriever, ragEnabled)) {
            return runtime.run(AgentRunContext.tenant(tenantId), requestId, system(), task(input), Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .join();
        }
    }

    private static AgentRuntime runtime(CapturingModel model, RagRetriever retriever, boolean ragEnabled) {
        return AgentRuntime.builder()
                .chatModel(model)
                .ragRuntimeComponents(new RagRuntimeComponents(retriever, new NoopIndexer()))
                .ragEnabled(ragEnabled)
                .telemetryEnabled(false)
                .build();
    }

    private static AgentSystem system() {
        Agent assistant = Agent.named("assistant").instructedBy("Answer.").build();
        GatewayAgent gateway = GatewayAgent.named("gateway")
                .accepts(Goal.of("agent.request").maxIterations(1).build())
                .delegatesTo(assistant)
                .build();
        return AgentSystem.builder().entrypoint(gateway).agent(assistant).build();
    }

    private static GoalRequest task(String input) {
        return GoalRequest.of("agent.request").instructions(input).build();
    }

    private static RetrievedChunk chunk(String tenantId, String documentId, String uri) {
        return new RetrievedChunk(
                tenantId,
                documentId,
                documentId + ":0",
                "retrieved fact",
                0.9,
                new RagChunkMetadata("test", "Doc", uri, "1", "intro", "hash", Instant.EPOCH, Instant.EPOCH, Map.of())
        );
    }

    private static final class CapturingModel implements ChatModel {
        private final AtomicInteger responses = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public String chat(String prompt) {
            prompts.add(prompt);
            return "response-" + responses.incrementAndGet();
        }

        List<String> prompts() {
            return prompts;
        }
    }

    private static final class CountingRetriever implements RagRetriever {
        private final RagRetrievalResult result;
        private final RuntimeException failure;
        private int calls;
        private String lastTenant = "";

        private CountingRetriever(RagRetrievalResult result) {
            this.result = result;
            this.failure = null;
        }

        private CountingRetriever(RuntimeException failure) {
            this.result = RagRetrievalResult.empty();
            this.failure = failure;
        }

        @Override
        public CompletionStage<RagRetrievalResult> retrieve(String query, RagSecurityContext securityContext, int topK) {
            calls++;
            lastTenant = securityContext.tenantId();
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(result);
        }

        int calls() {
            return calls;
        }

        String lastTenant() {
            return lastTenant;
        }
    }

    private static final class NoopIndexer implements RagIndexer {
        @Override
        public CompletionStage<RagIndexResult> index(IndexDocumentRequest request) {
            return CompletableFuture.completedFuture(new RagIndexResult(request.tenantId(), request.documentId(), 0, true));
        }

        @Override
        public CompletionStage<RagIndexResult> reindex(IndexDocumentRequest request) {
            return index(request);
        }

        @Override
        public CompletionStage<Void> delete(String tenantId, String documentId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
