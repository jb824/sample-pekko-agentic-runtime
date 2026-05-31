package com.example.adapter.inbound;

public record NewsArticlePayload(
        String articleId,
        String title,
        String url
) {
}
