package com.agentic.shortener;

import java.time.Instant;

public record ShortUrl(
		String code,
		String longUrl,
		Instant createdAt,
		long clicks,
		Instant lastClickedAt,
		Instant expiresAt,
		boolean deleted
) {
}
