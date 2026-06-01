package com.example.agent.tool;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@AgentTool(name = ToolCatalog.TIME_NOW, sourceCapable = false, timeoutSeconds = 5, retryAttempts = 0)
public final class TimeToolHandler {
    private final Clock clock;

    public TimeToolHandler(Clock clock) {
        this.clock = clock;
    }

    public ToolProtocol.ToolResult invoke(ToolProtocol.InvokeTool command) {
        String zone = command.arguments().getOrDefault("zone", "UTC");
        try {
            ZoneId zoneId = ZoneId.of(zone);
            Instant now = clock.instant();
            String output = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zoneId));
            return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), output, null);
        } catch (RuntimeException exception) {
            return new ToolProtocol.ToolResult(command.requestId(), command.toolName(), "", exception);
        }
    }
}
