package com.example.agent.http;

import org.apache.pekko.http.javadsl.server.directives.SecurityDirectives;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface PrincipalExtractor {
    CompletionStage<Optional<Principal>> authenticate(Optional<SecurityDirectives.ProvidedCredentials> credentials);

    default CompletionStage<Optional<Principal>> authenticateBearer(Optional<String> authorizationHeader) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
