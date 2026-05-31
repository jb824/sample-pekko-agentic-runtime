package com.example.adapter.inbound;

public record GoogleBusinessProfilePayload(
        String accountId,
        String locationId,
        String reviewId,
        String reviewerDisplayName,
        String starRating,
        String comment,
        String updateTime
) {
}
