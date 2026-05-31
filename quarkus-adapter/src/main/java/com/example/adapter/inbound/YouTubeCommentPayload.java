package com.example.adapter.inbound;

public record YouTubeCommentPayload(
        String channelId,
        String videoId,
        String commentId,
        String authorDisplayName,
        String textDisplay,
        String publishedAt,
        Long likeCount
) {
}
