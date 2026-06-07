package com.example.agent.http;

import java.util.Map;
import java.util.Set;

public record Principal(String subject, Set<String> roles, Map<String, String> claims) {
    public Principal {
        subject = subject == null || subject.isBlank() ? "anonymous" : subject;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }

    public boolean hasAnyRole(Set<String> requiredRoles) {
        return requiredRoles == null || requiredRoles.isEmpty()
                || requiredRoles.stream().anyMatch(roles::contains);
    }
}
