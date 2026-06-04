package com.example.agent.rag.core;

import java.util.Map;

public record RagSourceMetadata(
        String source,
        String title,
        String uri,
        String page,
        String section,
        Map<String, String> attributes
) {
    public RagSourceMetadata {
        source = source == null ? "" : source;
        title = title == null ? "" : title;
        uri = uri == null ? "" : uri;
        page = page == null ? "" : page;
        section = section == null ? "" : section;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
