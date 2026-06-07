package com.example.agent.tools;

import com.example.agent.api.AgentToolDefinition;
import com.example.agent.api.AgentToolRequest;
import com.example.agent.api.AgentToolResult;
import com.example.agent.api.FunctionTools;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebToolsTest {
    @Test
    void discoversAnnotatedWebTools() {
        List<AgentToolDefinition> tools = FunctionTools.from(newWebTools(new FakeTransport()));

        assertEquals(List.of("web.search", "web.fetch"), tools.stream().map(AgentToolDefinition::name).toList());
        assertTrue(tools.stream().allMatch(AgentToolDefinition::sourceCapable));
    }

    @Test
    void rejectsNonHttpsUrlBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        AgentToolResult result = fetch(newWebTools(transport), "http://example.com");

        assertFalse(result.isSuccess());
        assertInstanceOf(IllegalArgumentException.class, result.error());
        assertEquals(0, transport.requests.size());
    }

    @Test
    void rejectsPrivateResolvedAddressBeforeTransport() {
        WebTools tools = new WebTools(
                new FakeTransport(),
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}
        );

        AgentToolResult result = fetch(tools, "https://example.com");

        assertFalse(result.isSuccess());
        assertInstanceOf(IllegalArgumentException.class, result.error());
    }

    @Test
    void validatesEveryRedirectHop() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new WebTools.WebResponse(
                302,
                URI.create("https://example.com/start"),
                Map.of("location", List.of("https://private.test/secret")),
                new byte[0]
        ));
        WebTools tools = new WebTools(
                transport,
                host -> {
                    if ("private.test".equals(host)) {
                        return new InetAddress[]{InetAddress.getByName("10.0.0.5")};
                    }
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
        );

        AgentToolResult result = fetch(tools, "https://example.com/start");

        assertFalse(result.isSuccess());
        assertInstanceOf(IllegalArgumentException.class, result.error());
        assertEquals(List.of(URI.create("https://example.com/start")), transport.requests);
    }

    @Test
    void fetchReturnsSanitizedTextAndSource() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new WebTools.WebResponse(
                200,
                URI.create("https://example.com/page"),
                Map.of("content-type", List.of("text/html; charset=utf-8")),
                """
                        <html>
                          <head>
                            <title>Example Page</title>
                            <link rel="canonical" href="https://example.com/canonical">
                            <script>alert(1)</script>
                          </head>
                          <body><h1>Hello</h1><p>World</p></body>
                        </html>
                        """
                        .getBytes(StandardCharsets.UTF_8)
        ));

        AgentToolResult result = fetch(newWebTools(transport), "https://example.com/page");

        assertTrue(result.isSuccess());
        assertTrue(result.output().contains("Title: Example Page"));
        assertTrue(result.output().contains("Canonical URL: https://example.com/canonical"));
        assertTrue(result.output().contains("Hello"));
        assertTrue(result.output().contains("World"));
        assertFalse(result.output().contains("alert(1)"));
        assertEquals(List.of("https://example.com/page"), result.sources());
    }

    @Test
    void rejectsUnsupportedContentType() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new WebTools.WebResponse(
                200,
                URI.create("https://example.com/file"),
                Map.of("content-type", List.of("application/octet-stream")),
                new byte[]{1, 2, 3}
        ));

        AgentToolResult result = fetch(newWebTools(transport), "https://example.com/file");

        assertFalse(result.isSuccess());
        assertInstanceOf(IllegalArgumentException.class, result.error());
    }

    private static WebTools newWebTools(FakeTransport transport) {
        return new WebTools(
                transport,
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")}
        );
    }

    private static AgentToolResult fetch(WebTools tools, String url) {
        return FunctionTools.from(tools).stream()
                .filter(tool -> tool.name().equals("web.fetch"))
                .findFirst()
                .orElseThrow()
                .handler()
                .invoke(new AgentToolRequest("request-1", "tenant-a", "web.fetch", "", Map.of("url", url)))
                .toCompletableFuture()
                .join();
    }

    private static final class FakeTransport implements WebTools.WebTransport {
        final List<URI> requests = new ArrayList<>();
        final List<WebTools.WebResponse> responses = new ArrayList<>();

        @Override
        public CompletionStage<WebTools.WebResponse> get(
                URI uri,
                Map<String, String> headers,
                Duration timeout,
                int maxBytes
        ) {
            requests.add(uri);
            if (responses.isEmpty()) {
                return CompletableFuture.completedFuture(new WebTools.WebResponse(
                        200,
                        uri,
                        Map.of("content-type", List.of("text/html")),
                        "<html><body>ok</body></html>".getBytes(StandardCharsets.UTF_8)
                ));
            }
            return CompletableFuture.completedFuture(responses.removeFirst());
        }
    }
}
