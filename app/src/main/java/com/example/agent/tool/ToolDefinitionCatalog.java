package com.example.agent.tool;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.SupervisorStrategy;

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

        ActorRef<ToolProtocol.Command> webSearchTool = context.spawn(
                supervised(WebSearchToolActor.create()),
                "web-search-tool"
        );
        register(definitions, WebSearchToolActor.class, webSearchTool::tell);

        ActorRef<ToolProtocol.Command> arxivSearchTool = context.spawn(
                supervised(ArxivSearchToolActor.create()),
                "arxiv-search-tool"
        );
        register(definitions, ArxivSearchToolActor.class, arxivSearchTool::tell);

        ActorRef<ToolProtocol.Command> pubMedSearchTool = context.spawn(
                supervised(PubMedSearchToolActor.create()),
                "pubmed-search-tool"
        );
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

    private static Behavior<ToolProtocol.Command> supervised(Behavior<ToolProtocol.Command> behavior) {
        return Behaviors.supervise(behavior)
                .onFailure(SupervisorStrategy.restartWithBackoff(
                        java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(10),
                        0.2
                ));
    }
}
