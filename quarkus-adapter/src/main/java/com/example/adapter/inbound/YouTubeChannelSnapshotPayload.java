package com.example.adapter.inbound;

public record YouTubeChannelSnapshotPayload(
        String channelId,
        String title,
        String description,
        String country,
        String customUrl,
        String publishedAt,
        Long subscriberCount,
        boolean hiddenSubscriberCount,
        Long viewCount,
        Long videoCount,
        Long commentCount,
        String uploadsPlaylistId
) {
}
