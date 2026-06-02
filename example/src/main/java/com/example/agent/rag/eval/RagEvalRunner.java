package com.example.agent.rag.eval;

import com.example.agent.rag.HttpRagRetrievalClient;
import com.example.agent.rag.RagRetrieveRequest;
import com.example.agent.rag.RagRetrieveResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RagEvalRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RagEvalRunner() {
    }

    public static void main(String[] args) throws Exception {
        String datasetPath = System.getenv().getOrDefault("RAG_EVAL_DATASET", "config/rag-eval.json");
        String serviceUrl = System.getenv().getOrDefault("RAG_RETRIEVAL_URL", "http://localhost:8090");
        int topK = Integer.parseInt(System.getenv().getOrDefault("RAG_EVAL_TOP_K", "5"));

        List<EvalCase> cases = List.of(JSON.readValue(Files.readString(Path.of(datasetPath)), EvalCase[].class));
        HttpRagRetrievalClient client = new HttpRagRetrievalClient(serviceUrl, Duration.ofSeconds(10), "");

        int totalRelevant = 0;
        int totalFound = 0;
        double mrrTotal = 0.0;

        for (EvalCase testCase : cases) {
            RagRetrieveResponse response = client.retrieve(new RagRetrieveRequest(
                    testCase.tenantId(),
                    testCase.query(),
                    topK,
                    testCase.collection(),
                    java.util.Map.of()
            )).toCompletableFuture().join();

            Set<String> expected = Set.copyOf(testCase.relevantIds());
            List<String> returned = response.documents().stream().map(doc -> doc.id()).toList();
            totalRelevant += expected.size();
            totalFound += returned.stream().filter(expected::contains).count();
            mrrTotal += reciprocalRank(returned, expected);
        }

        double recallAtK = totalRelevant == 0 ? 0.0 : totalFound / (double) totalRelevant;
        double mrr = cases.isEmpty() ? 0.0 : mrrTotal / cases.size();
        System.out.println("RAG_EVAL recall_at_k=" + recallAtK + " mrr=" + mrr + " cases=" + cases.size());
    }

    private static double reciprocalRank(List<String> returnedIds, Set<String> expected) {
        for (int i = 0; i < returnedIds.size(); i++) {
            if (expected.contains(returnedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private record EvalCase(
            String tenantId,
            String collection,
            String query,
            List<String> relevantIds
    ) {
    }
}
