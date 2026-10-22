package com.agentic.shortener;

import java.time.Instant;

public record ShortUrl(
		String code,
		String longUrl,
		Instant createdAt,
		long clicks,
		Instant lastClickedAt
) {
	public ShortUrl withClick() {
		return new ShortUrl(code, longUrl, createdAt, clicks + 1, Instant.now());
	}
}
