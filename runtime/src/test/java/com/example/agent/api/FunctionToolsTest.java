package com.example.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionToolsTest {
    @Test
    void scansPrivateNoArgMethod() {
        List<AgentToolDefinition> tools = FunctionTools.from(new DateTools());

        AgentToolDefinition tool = tools.getFirst();
        AgentToolResult result = tool.handler().invoke(request(tool.name(), "", Map.of())).toCompletableFuture().join();

        assertEquals("currentDate", tool.name());
        assertEquals("Return current date in yyyy-MM-dd format", tool.description());
        assertEquals("2026-06-06", result.output());
    }

    @Test
    void explicitNameOverridesMethodName() {
        AgentToolDefinition tool = FunctionTools.from(new EchoTools()).getFirst();

        assertEquals("custom.echo", tool.name());
    }

    @Test
    void objectAndClassSourcesBothWork() {
        List<AgentToolDefinition> tools = FunctionTools.from(new EchoTools(), LazyTools.class);

        assertEquals(List.of("custom.echo", "lazy"), tools.stream().map(AgentToolDefinition::name).toList());
    }

    @Test
    void bindsRequestStringAndNamedArguments() {
        Map<String, String> args = Map.of("left", "2", "right", "5", "shout", "true");
        List<AgentToolDefinition> tools = FunctionTools.from(new BindingTools());

        AgentToolResult request = invoke(tools, "tenant", request("tenant", "hello", Map.of()));
        AgentToolResult string = invoke(tools, "input", request("input", "hello", Map.of()));
        AgentToolResult named = invoke(tools, "sum", request("sum", "", args));

        assertEquals("tenant-a", request.output());
        assertEquals("hello", string.output());
        assertEquals("7!", named.output());
    }

    @Test
    void bindsMethodLevelParamVariables() {
        Map<String, String> args = Map.of("zone", "America/New_York", "format", "HH:mm:ss");
        List<AgentToolDefinition> tools = FunctionTools.from(new MethodParamTools());

        AgentToolDefinition tool = tools.getFirst();
        AgentToolResult result = tool.handler().invoke(request("formatTime", "", args)).toCompletableFuture().join();

        assertEquals("America/New_York|HH:mm:ss", result.output());
        assertEquals(
                "Formats time. Arguments: zone: IANA time zone; format: Date/time format.",
                tool.description()
        );
    }

    @Test
    void mapsCompletionStageResultAndExceptions() {
        List<AgentToolDefinition> tools = FunctionTools.from(new AsyncTools());

        AgentToolResult success = invoke(tools, "async", request("async", "", Map.of()));
        AgentToolResult failure = invoke(tools, "explode", request("explode", "", Map.of()));

        assertEquals("async-ok", success.output());
        assertTrue(failure.error() instanceof IllegalStateException);
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThrows(IllegalArgumentException.class, () -> FunctionTools.from(new EchoTools(), new DuplicateEchoTools()));
    }

    @Test
    void rejectsSourcesWithoutAnnotatedMethods() {
        assertThrows(IllegalArgumentException.class, () -> FunctionTools.from(new Object()));
    }

    private static AgentToolResult invoke(List<AgentToolDefinition> tools, String name, AgentToolRequest request) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow()
                .handler()
                .invoke(request)
                .toCompletableFuture()
                .join();
    }

    private static AgentToolRequest request(String toolName, String input, Map<String, String> args) {
        return new AgentToolRequest("request-1", "tenant-a", toolName, input, args);
    }

    private static final class DateTools {
        @Tool(description = "Return current date in yyyy-MM-dd format")
        private String currentDate() {
            return "2026-06-06";
        }
    }

    private static final class EchoTools {
        @Tool(name = "custom.echo", description = "Echoes input.")
        String echo(String input) {
            return input;
        }
    }

    private static final class DuplicateEchoTools {
        @Tool(name = "custom.echo", description = "Duplicate.")
        String duplicate() {
            return "duplicate";
        }
    }

    private static final class LazyTools {
        @Tool(description = "Lazy class tool.")
        String lazy() {
            return "lazy";
        }
    }

    private static final class BindingTools {
        @Tool(description = "Gets tenant.")
        String tenant(AgentToolRequest request) {
            return request.tenantId();
        }

        @Tool(description = "Gets input.")
        String input(String input) {
            return input;
        }

        @Tool(description = "Adds values.")
        String sum(
                @ToolParam(name = "left", description = "Left number") int left,
                @ToolParam(name = "right", description = "Right number") Integer right,
                @ToolParam(name = "shout", description = "Add punctuation") boolean shout
        ) {
            return left + right + (shout ? "!" : "");
        }
    }

    private static final class MethodParamTools {
        @Tool(
                description = "Formats time.",
                params = {
                        @ToolParam(name = "zone", description = "IANA time zone"),
                        @ToolParam(name = "format", description = "Date/time format")
                }
        )
        String formatTime(String zone, String format) {
            return zone + "|" + format;
        }
    }

    private static final class AsyncTools {
        @Tool(description = "Async result.")
        CompletableFuture<String> async() {
            return CompletableFuture.completedFuture("async-ok");
        }

        @Tool(description = "Explodes.")
        String explode() {
            throw new IllegalStateException("boom");
        }
    }
}
