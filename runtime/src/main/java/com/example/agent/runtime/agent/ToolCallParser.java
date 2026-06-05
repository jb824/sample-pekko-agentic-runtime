package com.example.agent.runtime.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class ToolCallParser {
    private ToolCallParser() {
    }

    static Optional<ToolCall> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] lines = text.strip().split("\\R");
        String first = lines[0].trim();
        if (!first.regionMatches(true, 0, "TOOL:", 0, "TOOL:".length())) {
            return Optional.empty();
        }
        String toolName = first.substring("TOOL:".length()).trim();
        if (toolName.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (!line.regionMatches(true, 0, "ARG ", 0, "ARG ".length())) {
                continue;
            }
            String argument = line.substring("ARG ".length()).trim();
            int separator = argument.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = argument.substring(0, separator).trim();
            String value = argument.substring(separator + 1).trim();
            if (!key.isBlank()) {
                arguments.put(key, value);
            }
        }
        return Optional.of(new ToolCall(toolName, arguments));
    }

    static String finalText(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.regionMatches(true, 0, "FINAL:", 0, "FINAL:".length())) {
            return stripped.substring("FINAL:".length()).strip();
        }
        return stripped;
    }

    record ToolCall(String toolName, Map<String, String> arguments) {
        ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
}
