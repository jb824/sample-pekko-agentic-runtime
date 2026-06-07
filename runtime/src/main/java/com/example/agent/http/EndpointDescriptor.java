package com.example.agent.http;

import java.util.List;

record EndpointDescriptor(Object instance, String prefix, List<EndpointMethodDescriptor> methods) {
    EndpointDescriptor {
        if (instance == null) {
            throw new IllegalArgumentException("endpoint instance is required");
        }
        prefix = normalize(prefix);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("endpoint prefix must not be blank");
        }
        String normalized = path.trim();
        normalized = normalized.startsWith("/") ? normalized : "/" + normalized;
        return normalized.endsWith("/") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
