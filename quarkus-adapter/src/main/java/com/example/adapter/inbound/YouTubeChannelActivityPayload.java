package com.example.adapter.inbound;

public record YouTubeChannelActivityPayload(
        String activityId,
        String channelId,
        String channelTitle,
        String activityType,
        String title,
        String description,
        String publishedAt,
        String videoId,
        String playlistId,
        String playlistItemId,
        String targetChannelId,
        String recommendationReason
) {
}
