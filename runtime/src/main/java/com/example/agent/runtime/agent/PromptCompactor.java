package com.example.agent.runtime.agent;

import java.util.List;

final class PromptCompactor {
    private PromptCompactor() {
    }

    static String fit(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int safeMax = Math.max(32, maxChars);
        String stripped = value.strip();
        if (stripped.length() <= safeMax) {
            return stripped;
        }
        String marker = "\n[truncated " + (stripped.length() - safeMax) + " chars to fit context budget]\n";
        int remaining = Math.max(0, safeMax - marker.length());
        if (remaining <= 0) {
            return marker.strip();
        }
        int head = Math.max(0, remaining / 2);
        int tail = Math.max(0, remaining - head);
        return stripped.substring(0, head).stripTrailing()
                + marker
                + stripped.substring(stripped.length() - tail).stripLeading();
    }

    static String fitSections(List<String> sections, int maxChars) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        int safeMax = Math.max(32, maxChars);
        int perSection = Math.max(64, safeMax / sections.size());
        String joined = sections.stream()
                .map(section -> fit(section, perSection))
                .filter(section -> !section.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        return fit(joined, safeMax);
    }
}
