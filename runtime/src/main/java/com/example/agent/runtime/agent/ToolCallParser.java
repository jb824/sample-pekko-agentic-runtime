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
        int querySeparator = toolName.indexOf('?');
        if (querySeparator > 0) {
            parseInlineArguments(toolName.substring(querySeparator + 1), arguments);
            toolName = toolName.substring(0, querySeparator).trim();
        }
        int whitespaceSeparator = firstWhitespace(toolName);
        if (whitespaceSeparator > 0) {
            parseInlineArguments(toolName.substring(whitespaceSeparator + 1), arguments);
            toolName = toolName.substring(0, whitespaceSeparator).trim();
        }
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

    private static void parseInlineArguments(String text, Map<String, String> arguments) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String token : text.trim().split("[&\\s]+")) {
            int separator = token.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = token.substring(0, separator).trim();
            String value = token.substring(separator + 1).trim();
            if (!key.isBlank()) {
                arguments.putIfAbsent(key, value);
            }
        }
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
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

    static Optional<String> embeddedFinalText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] lines = text.strip().split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (!line.regionMatches(true, 0, "FINAL:", 0, "FINAL:".length())) {
                continue;
            }
            StringBuilder finalText = new StringBuilder(line.substring("FINAL:".length()).strip());
            for (int next = index + 1; next < lines.length; next++) {
                if (!finalText.isEmpty()) {
                    finalText.append('\n');
                }
                finalText.append(lines[next]);
            }
            String output = finalText.toString().strip();
            return output.isBlank() ? Optional.empty() : Optional.of(output);
        }
        return Optional.empty();
    }

    record ToolCall(String toolName, Map<String, String> arguments) {
        ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
}
