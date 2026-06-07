package com.example.agent.http;

import org.apache.pekko.http.javadsl.server.directives.SecurityDirectives;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class DevBearerPrincipalExtractor implements PrincipalExtractor {
    public static final DevBearerPrincipalExtractor INSTANCE = new DevBearerPrincipalExtractor();

    private DevBearerPrincipalExtractor() {
    }

    @Override
    public CompletionStage<Optional<Principal>> authenticate(Optional<SecurityDirectives.ProvidedCredentials> credentials) {
        return CompletableFuture.completedFuture(credentials
                .map(SecurityDirectives.ProvidedCredentials::identifier)
                .filter(token -> !token.isBlank())
                .map(this::principal));
    }

    @Override
    public CompletionStage<Optional<Principal>> authenticateBearer(Optional<String> authorizationHeader) {
        return CompletableFuture.completedFuture(authorizationHeader
                .map(String::trim)
                .filter(header -> header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()))
                .map(header -> header.substring("Bearer ".length()).trim())
                .filter(token -> !token.isBlank())
                .map(this::principal));
    }

    Principal principal(String token) {
        String subject = "bearer";
        String roleText = token;
        int separator = token.indexOf(':');
        if (separator < 0) {
            separator = token.indexOf('~');
        }
        if (separator > 0) {
            subject = token.substring(0, separator).trim();
            roleText = token.substring(separator + 1).trim();
        }
        Set<String> roles = Arrays.stream(roleText.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new Principal(subject, roles, Map.of("auth", "dev-bearer"));
    }
}
