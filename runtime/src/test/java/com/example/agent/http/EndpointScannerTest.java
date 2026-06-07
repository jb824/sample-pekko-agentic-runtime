package com.example.agent.http;

import com.example.agent.api.http.Get;
import com.example.agent.api.http.HttpEndpoint;
import com.example.agent.api.http.Post;
import com.example.agent.api.http.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EndpointScannerTest {
    @Test
    void scansHttpEndpointMethodsAndRoles() {
        EndpointDescriptor descriptor = EndpointScanner.scan(new SampleEndpoint());

        assertEquals("/sample", descriptor.prefix());
        assertEquals(2, descriptor.methods().size());
        EndpointMethodDescriptor invoke = descriptor.methods().stream()
                .filter(method -> method.httpMethod() == HttpMethod.POST)
                .findFirst()
                .orElseThrow();
        assertEquals("/invoke", invoke.path());
        assertEquals(java.util.Set.of("agent-user", "admin"), invoke.roles());
    }

    @Test
    void rejectsDuplicateRoutes() {
        assertThrows(IllegalArgumentException.class, () -> EndpointScanner.scan(new DuplicateEndpoint()));
    }

    @Test
    void devBearerPrincipalUsesTokenRoles() {
        var principal = DevBearerPrincipalExtractor.INSTANCE.principal("alice:agent-user,admin");

        assertEquals("alice", principal.subject());
        assertEquals(java.util.Set.of("agent-user", "admin"), principal.roles());
    }

    @Test
    void devBearerPrincipalAcceptsAuthorizationHeaderWithColonToken() {
        var principal = DevBearerPrincipalExtractor.INSTANCE.authenticateBearer(Optional.of("Bearer alice:public"))
                .toCompletableFuture()
                .join()
                .orElseThrow();

        assertEquals("alice", principal.subject());
        assertEquals(java.util.Set.of("public"), principal.roles());
    }

    @HttpEndpoint("/sample")
    @RolesAllowed("agent-user")
    public static final class SampleEndpoint {
        @Post("/invoke")
        @RolesAllowed("admin")
        public String invoke(String input) {
            return input;
        }

        @Get("/items/{id}")
        public String item(String id) {
            return id;
        }
    }

    @HttpEndpoint("/duplicate")
    public static final class DuplicateEndpoint {
        @Get("/same")
        public String first() {
            return "first";
        }

        @Get("/same")
        public String second() {
            return "second";
        }
    }
}
