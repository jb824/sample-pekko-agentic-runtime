package com.example.agent.runtime.agent;

public record PromptBudget(
        int contextWindowTokens,
        int reservedOutputTokens,
        int safetyTokens
) {
    private static final int CHARS_PER_TOKEN = 4;

    public PromptBudget {
        contextWindowTokens = Math.max(1024, contextWindowTokens);
        reservedOutputTokens = Math.max(0, reservedOutputTokens);
        safetyTokens = Math.max(0, safetyTokens);
    }

    public static PromptBudget disabled() {
        return new PromptBudget(Integer.MAX_VALUE / CHARS_PER_TOKEN, 0, 0);
    }

    public int maxPromptChars() {
        long availableTokens = (long) contextWindowTokens - reservedOutputTokens - safetyTokens;
        if (availableTokens <= 0) {
            return CHARS_PER_TOKEN;
        }
        long chars = availableTokens * CHARS_PER_TOKEN;
        return chars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) chars;
    }
}
