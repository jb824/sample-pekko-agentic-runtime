package com.example.adapter.inbound;

import jakarta.validation.constraints.NotBlank;

public record MockGoogleBusinessProfileReviewRequest(
        String tenantId,
        @NotBlank String accountId,
        @NotBlank String locationId,
        @NotBlank String reviewId,
        String reviewerDisplayName,
        String starRating,
        String comment,
        String updateTime
) {
}
