package com.example.agent.guardian;

import java.time.Instant;

public record GuardianArticle(
        String id,
        String sectionId,
        String sectionName,
        String webTitle,
        String webUrl,
        Instant webPublicationDate,
        String headline,
        String trailText,
        String byline,
        String publication
) {
}
