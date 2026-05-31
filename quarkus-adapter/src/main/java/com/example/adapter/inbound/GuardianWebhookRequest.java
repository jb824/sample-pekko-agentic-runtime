package com.example.adapter.inbound;

import jakarta.validation.constraints.NotBlank;

public record GuardianWebhookRequest(
        String tenantId,
        @NotBlank String articleId,
        @NotBlank String title,
        @NotBlank String url
) {
}
