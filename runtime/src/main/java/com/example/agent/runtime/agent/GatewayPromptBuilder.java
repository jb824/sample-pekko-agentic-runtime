package com.example.agent.runtime.agent;

import com.example.agent.api.GatewayAgent;
import com.example.agent.protocol.AgentRequest;
import com.example.agent.runtime.memory.AgentMemoryEvent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class GatewayPromptBuilder {
    private GatewayPromptBuilder() {
    }

    static String build(
            AgentRequest request,
            GatewayAgent gateway,
            Map<String, String> delegateOutputs,
            List<AgentMemoryEvent> memory,
            String ragContext
    ) {
        String delegateContext = delegateOutputs == null || delegateOutputs.isEmpty()
                ? "No delegate outputs."
                : delegateOutputs.entrySet().stream()
                .map(entry -> "Agent " + entry.getKey() + ":\n" + truncate(entry.getValue()))
                .collect(Collectors.joining("\n\n"));
        String memorySection = memory == null || memory.isEmpty()
                ? "None."
                : memory.stream()
                .map(event -> "- [" + event.type() + "] " + truncate(event.content()))
                .collect(Collectors.joining("\n"));
        return """
                You are gateway agent "%s".
                Instructions: %s

                User task:
                %s

                Task: %s — %s

                Memory from previous sessions:
                %s

                Retrieved knowledge:
                %s

                Delegate agent outputs:
                %s

                Produce the final answer for the user.
                """.formatted(
                safe(gateway.name()),
                safe(gateway.instructions()),
                request.input(),
                safe(gateway.acceptedTask().name()),
                safe(gateway.acceptedTask().description()),
                memorySection,
                ragContext == null || ragContext.isBlank() ? "None." : ragContext,
                delegateContext);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= 1200 ? stripped : stripped.substring(0, 1200) + "...";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
