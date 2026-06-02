package com.example.agent.tool;

import com.example.agent.config.AppConfig;
import com.example.agent.rag.HttpRagRetrievalClient;
import com.example.agent.rag.RagDocument;
import com.example.agent.rag.RagRetrieveRequest;
import com.example.agent.rag.RagRetrievalClient;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@AgentTool(name = ToolCatalog.RAG_RETRIEVE, sourceCapable = true, timeoutSeconds = 10, retryAttempts = 1)
public final class RagRetrieveToolActor extends AbstractBehavior<ToolProtocol.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(RagRetrieveToolActor.class);
    private final RagRetrievalClient client;
    private final String defaultTenant;
    private final String defaultCollection;

    public static Behavior<ToolProtocol.Command> create(AppConfig config) {
        String tenant = config.ragTenantId();
        String collection = config.ragCollection();
        RagRetrievalClient client = new HttpRagRetrievalClient(
                config.ragRetrievalUrl(),
                Duration.ofMillis(config.ragServiceTimeoutMs()),
                config.ragRetrievalApiKey()
        );
        return Behaviors.setup(context -> new RagRetrieveToolActor(context, client, tenant, collection));
    }

    private RagRetrieveToolActor(
            ActorContext<ToolProtocol.Command> context,
            RagRetrievalClient client,
            String defaultTenant,
            String defaultCollection
    ) {
        super(context);
        this.client = client;
        this.defaultTenant = defaultTenant;
        this.defaultCollection = defaultCollection;
    }

    @Override
    public Receive<ToolProtocol.Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ToolProtocol.InvokeTool.class, this::onInvokeTool)
                .build();
    }

    private Behavior<ToolProtocol.Command> onInvokeTool(ToolProtocol.InvokeTool command) {
        String query = command.arguments().getOrDefault("query", "");
        String tenantId = command.arguments().getOrDefault("tenantId", defaultTenant);
        String collection = command.arguments().getOrDefault("collection", defaultCollection);
        int topK = parseTopK(command.arguments().get("topK"));
        LOG.info(
                "rag.retrieve invoke request_id={} tenant={} collection={} topK={} query='{}'",
                command.requestId(),
                tenantId,
                collection,
                topK,
                truncate(query, 240)
        );
        if (query.isBlank()) {
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    "",
                    new IllegalArgumentException("rag.retrieve requires a non-empty query")
            ));
            return this;
        }
        RagRetrieveRequest request = new RagRetrieveRequest(tenantId, query, topK, collection, Map.of());
        client.retrieve(request).whenComplete((response, failure) -> {
            if (failure != null) {
                LOG.warn(
                        "rag.retrieve failed request_id={} tenant={} collection={} error={}",
                        command.requestId(),
                        tenantId,
                        collection,
                        failure.toString()
                );
                command.replyTo().tell(new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "", failure));
                return;
            }
            LOG.info(
                    "rag.retrieve success request_id={} tenant={} collection={} docs={}",
                    command.requestId(),
                    tenantId,
                    collection,
                    response.documents() == null ? 0 : response.documents().size()
            );
            command.replyTo().tell(new ToolProtocol.ToolResult(
                    command.requestId(),
                    command.toolName(),
                    format(response.documents()),
                    null
            ));
        });
        return this;
    }

    private static int parseTopK(String value) {
        if (value == null || value.isBlank()) {
            return 5;
        }
        return Math.max(1, Math.min(20, Integer.parseInt(value)));
    }

    private static String format(List<RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No RAG results returned.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int i = 0; i < documents.size(); i++) {
            RagDocument document = documents.get(i);
            lines.add((i + 1) + ". " + blankSafe(document.title(), "Untitled"));
            lines.add("   URL: " + blankSafe(document.reference(), "<unknown>"));
            lines.add("   Snippet: " + blankSafe(document.snippet(), ""));
            lines.add("   Score: " + document.score());
            lines.add("   Source: " + blankSafe(document.source(), "rag"));
        }
        return String.join("\n", lines);
    }

    private static String blankSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
