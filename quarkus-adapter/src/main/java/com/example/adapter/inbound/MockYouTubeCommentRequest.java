package com.example.adapter.inbound;

import jakarta.validation.constraints.NotBlank;

public record MockYouTubeCommentRequest(
        String tenantId,
        @NotBlank String channelId,
        String videoId,
        @NotBlank String commentId,
        String authorDisplayName,
        String textDisplay,
        String publishedAt,
        Long likeCount
) {
}
