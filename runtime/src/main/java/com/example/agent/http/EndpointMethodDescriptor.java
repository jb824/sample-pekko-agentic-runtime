package com.example.agent.http;

import java.lang.reflect.Method;
import java.util.Set;

record EndpointMethodDescriptor(
        HttpMethod httpMethod,
        String path,
        Method method,
        Set<String> roles
) {
    EndpointMethodDescriptor {
        if (httpMethod == null) {
            throw new IllegalArgumentException("http method is required");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("method path is required");
        }
        if (method == null) {
            throw new IllegalArgumentException("handler method is required");
        }
        path = path.trim().startsWith("/") ? path.trim() : "/" + path.trim();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
