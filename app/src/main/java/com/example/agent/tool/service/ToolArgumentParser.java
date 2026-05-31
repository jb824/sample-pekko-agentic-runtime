package com.example.agent.tool.service;

public final class ToolArgumentParser {
    private ToolArgumentParser() {
    }

    public static int parseBoundedPositiveInt(String value, int defaultValue, int maxValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Math.min(maxValue, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
