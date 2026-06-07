package com.example.agent.tools;

import com.example.agent.api.Tool;
import com.example.agent.api.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;


public class ScheduleTool {

    @Tool(description = "Return current date in yyyy-MM-dd format")
    private String getCurrentDate() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Tool(description = "Return current time in HH:MM:SS AM/PM format")
    private String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM));
    }

    @Tool(
            description = "Return current date in yyyy-MM-dd format for an IANA time zone",
            params = @ToolParam(
                    name = "zoneId",
                    description = "IANA time zone such as UTC or America/New_York"
            )
    )
    private String getCurrentDateWithZoneId(String zoneId) {
        zoneId = parseZoneId(zoneId);
        try {
            return ZonedDateTime.now(ZoneId.of(zoneId, ZoneId.SHORT_IDS))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        }  catch (DateTimeParseException e) {
            System.out.println(e.getMessage());
            return ZoneId.getAvailableZoneIds().toString();
        }
    }

    @Tool(
            description = "Return current time in HH:MM:SS AM/PM format for an IANA time zone like 'Europe/London",
            params = @ToolParam(name = "zoneId", description = "IANA time zone such as UTC or America/New_York")
    )
    private String getCurrentTimeWithZoneId(String zoneId) {
        zoneId = parseZoneId(zoneId);
        try {
            return ZonedDateTime.now(ZoneId.of(zoneId, ZoneId.SHORT_IDS))
                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM));
        }  catch (DateTimeParseException e) {
            System.out.println(e.getMessage());
            return ZoneId.getAvailableZoneIds().toString();
        }
    }

    @Tool(
            description = "Return current date and time parsed as RFC-1123 format (e.g., 'Tue, 3 Jun 2008 11:05:30 GMT')",
            params = @ToolParam(name = "zoneId", description = "IANA time zone such as UTC or America/New_York")
    )
    private String getCurrentDateTimeWithZoneId(String zoneId) {
        zoneId = parseZoneId(zoneId);
        try {
            return ZonedDateTime.now(ZoneId.of(zoneId, ZoneId.SHORT_IDS))
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        }  catch (DateTimeParseException e) {
            System.out.println(e.getMessage());
            return ZoneId.getAvailableZoneIds().toString();
        }
    }

    @Tool(description = "Return list of IANA Zone Ids")
    private String getIanaZoneIds() {
        return ZoneId.getAvailableZoneIds().toString();
    }

    private String parseZoneId(String zoneId) {
        if (zoneId.length() != 3) {
            String[] zoneArr = zoneId.split("[/]");
            return zoneArr[0].substring(0,1).toUpperCase() + zoneArr[0].substring(1)
                    + "/" + zoneArr[1].substring(0,1).toUpperCase() + zoneArr[1].substring(1);
        } else {
            return zoneId.toUpperCase();
        }
    }

}
