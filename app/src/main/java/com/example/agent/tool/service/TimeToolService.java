package com.example.agent.tool.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class TimeToolService implements ToolService {
    private final Clock clock;

    public TimeToolService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String execute(Map<String, String> arguments) {
        String zone = arguments.getOrDefault("zone", "UTC");
        ZoneId zoneId = ZoneId.of(zone);
        Instant now = clock.instant();
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zoneId));
    }
}
