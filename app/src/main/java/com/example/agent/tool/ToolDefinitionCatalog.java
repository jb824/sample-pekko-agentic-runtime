package com.example.agent.tool;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.ActorContext;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolDefinitionCatalog {
    private ToolDefinitionCatalog() {
    }

    public static Map<String, ToolDefinition> create(ActorContext<ToolProtocol.Command> context) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

        TimeToolHandler timeToolHandler = new TimeToolHandler(Clock.systemUTC());
        register(definitions, TimeToolHandler.class, command -> command.replyTo().tell(timeToolHandler.invoke(command)));

        ActorRef<ToolProtocol.Command> webSearchTool = context.spawn(WebSearchToolActor.create(), "web-search-tool");
        register(definitions, WebSearchToolActor.class, webSearchTool::tell);

        ActorRef<ToolProtocol.Command> arxivSearchTool = context.spawn(ArxivSearchToolActor.create(), "arxiv-search-tool");
        register(definitions, ArxivSearchToolActor.class, arxivSearchTool::tell);

        ActorRef<ToolProtocol.Command> pubMedSearchTool = context.spawn(PubMedSearchToolActor.create(), "pubmed-search-tool");
        register(definitions, PubMedSearchToolActor.class, pubMedSearchTool::tell);

        return Map.copyOf(definitions);
    }

    private static void register(Map<String, ToolDefinition> definitions, Class<?> type, ToolInvoker invoker) {
        AgentTool annotation = type.getAnnotation(AgentTool.class);
        if (annotation == null) {
            throw new IllegalStateException("Missing @AgentTool annotation on " + type.getName());
        }
        if (definitions.containsKey(annotation.name())) {
            throw new IllegalStateException("Duplicate tool definition: " + annotation.name());
        }
        definitions.put(
                annotation.name(),
                new ToolDefinition(
                        annotation.name(),
                        annotation.sourceCapable(),
                        Math.max(1, annotation.timeoutSeconds()),
                        Math.max(0, annotation.retryAttempts()),
                        invoker
                )
        );
    }
}
