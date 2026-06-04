package com.example.agent.rag.runtime;

import com.example.agent.rag.core.RagIndexer;
import com.example.agent.rag.core.RagRetriever;

public record RagRuntimeComponents(RagRetriever retriever, RagIndexer indexer) {
}
