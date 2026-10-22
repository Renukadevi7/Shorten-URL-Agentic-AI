package com.agentic.shortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UrlShortenerServiceTest {

	private final UrlShortenerService service = new UrlShortenerService();

	@Test
	void shortensAndResolves() {
		ShortUrl created = service.shorten("https://example.com");
		assertTrue(service.resolve(created.code()).isPresent());
		assertEquals("https://example.com", service.resolve(created.code()).orElseThrow().longUrl());
	}

	@Test
	void rejectsBadScheme() {
		assertThrows(IllegalArgumentException.class, () -> service.shorten("ftp://example.com"));
	}
}
