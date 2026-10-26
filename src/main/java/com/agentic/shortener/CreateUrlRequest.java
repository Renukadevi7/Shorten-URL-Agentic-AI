package com.agentic.shortener;

public record CreateUrlRequest(
		String url,
		String customAlias,
		Long ttlSeconds
) {
}
