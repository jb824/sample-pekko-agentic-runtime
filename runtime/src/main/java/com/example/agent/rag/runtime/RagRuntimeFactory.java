package com.example.agent.rag.runtime;

import com.example.agent.config.AppConfig;
import com.example.agent.rag.core.DefaultRagIndexer;
import com.example.agent.rag.core.DefaultRagRetriever;
import com.example.agent.rag.core.DisabledRagRetriever;
import com.example.agent.rag.core.DocumentChunker;
import com.example.agent.rag.core.EmbeddingClient;
import com.example.agent.rag.core.InMemoryVectorStore;
import com.example.agent.rag.core.RagIndexer;
import com.example.agent.rag.core.RagRetriever;
import com.example.agent.rag.core.SimpleHashEmbeddingClient;
import com.example.agent.rag.core.VectorStore;

public final class RagRuntimeFactory {
    private RagRuntimeFactory() {
    }

    public static RagRuntimeComponents create(AppConfig config) {
        VectorStore vectorStore = vectorStore(config);
        EmbeddingClient embeddingClient = embeddingClient(config);
        RagIndexer indexer = new DefaultRagIndexer(
                embeddingClient,
                vectorStore,
                new DocumentChunker(config.ragChunkMaxChars(), config.ragChunkOverlapChars())
        );
        RagRetriever retriever = config.ragEnabled()
                ? new DefaultRagRetriever(embeddingClient, vectorStore, config.ragMinScore())
                : new DisabledRagRetriever();
        return new RagRuntimeComponents(retriever, indexer);
    }

    private static EmbeddingClient embeddingClient(AppConfig config) {
        String provider = config.ragEmbeddingProvider();
        if ("in-memory".equalsIgnoreCase(provider) || "fake".equalsIgnoreCase(provider) || "simple-hash".equalsIgnoreCase(provider)) {
            return new SimpleHashEmbeddingClient(config.ragEmbeddingDimensions());
        }
        throw new IllegalArgumentException("Unsupported RAG embedding provider: " + provider + ". TODO: add intfloat E5 adapter.");
    }

    private static VectorStore vectorStore(AppConfig config) {
        String store = config.ragVectorStore();
        if ("in-memory".equalsIgnoreCase(store) || "fake".equalsIgnoreCase(store)) {
            return new InMemoryVectorStore();
        }
        if ("pgvector".equalsIgnoreCase(store)) {
            throw new IllegalArgumentException("pgvector is not implemented in this repo yet; add separate schema/migrations outside Pekko persistence.");
        }
        throw new IllegalArgumentException("Unsupported RAG vector store: " + store);
    }
}
