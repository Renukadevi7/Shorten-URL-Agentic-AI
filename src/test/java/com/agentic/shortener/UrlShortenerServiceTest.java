package com.agentic.shortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UrlShortenerServiceTest {

	@Autowired
	private UrlShortenerService service;

	@BeforeEach
	void reset() {
		service.resetProduct();
	}

	@Test
	void shortensWithCustomAliasAndExpiry() {
		ShortUrl created = service.shorten("https://example.com", "myalias", 60L);
		assertEquals("myalias", created.code());
		assertTrue(created.expiresAt() != null);
		assertTrue(service.resolve("myalias").isPresent());
	}

	@Test
	void rejectsDuplicateAlias() {
		service.shorten("https://example.com/a", "dup", null);
		assertThrows(IllegalArgumentException.class,
				() -> service.shorten("https://example.com/b", "dup", null));
	}

	@Test
	void deletePreventsResolve() {
		ShortUrl created = service.shorten("https://example.com", null, null);
		assertTrue(service.delete(created.code()));
		assertFalse(service.resolve(created.code()).isPresent());
	}

	@Test
	void rejectsLocalhost() {
		assertThrows(IllegalArgumentException.class, () -> service.shorten("http://localhost/x", null, null));
	}

	@Test
	void snapshotAndRestoreRollsBackProduct() {
		service.shorten("https://example.com/before", "before", null);
		ProductSnapshot snap = service.snapshot();
		service.shorten("https://example.com/after", "after", null);
		assertTrue(service.resolve("after").isPresent());
		service.restore(snap);
		assertFalse(service.resolve("after").isPresent());
		assertTrue(service.resolve("before").isPresent());
	}
}
