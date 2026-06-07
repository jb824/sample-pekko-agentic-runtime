package com.example.agent.runtime.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ToolCallParserTest {
    @Test
    void parsesQueryStyleInlineArguments() {
        var parsed = ToolCallParser.parse("TOOL: getIanaZoneIds?zoneId=Europe/London");

        assertTrue(parsed.isPresent());
        assertEquals("getIanaZoneIds", parsed.get().toolName());
        assertEquals("Europe/London", parsed.get().arguments().get("zoneId"));
    }

    @Test
    void parsesWhitespaceInlineArguments() {
        var parsed = ToolCallParser.parse("TOOL: getIanaZoneIds zoneId=Europe/London");

        assertTrue(parsed.isPresent());
        assertEquals("getIanaZoneIds", parsed.get().toolName());
        assertEquals("Europe/London", parsed.get().arguments().get("zoneId"));
    }
}
