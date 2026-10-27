package com.agentic.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ApiKeyStartupValidatorTest {

	@Test
	void rejectsBlankUnlessAllowBlank() {
		assertThrows(IllegalStateException.class,
				() -> new ApiKeyStartupValidator("", false).run(new DefaultApplicationArguments()));
		new ApiKeyStartupValidator("", true).run(new DefaultApplicationArguments());
	}

	@Test
	void rejectsWeakAndPlaceholderKeys() {
		assertTrue(ApiKeyStartupValidator.isWeak("test-api-key"));
		assertTrue(ApiKeyStartupValidator.isWeak("short"));
		assertTrue(ApiKeyStartupValidator.isWeak("demo-api-key-change-me"));
		assertTrue(ApiKeyStartupValidator.isWeak("password"));
		assertFalse(ApiKeyStartupValidator.isWeak("local-test-key-16c"));
		assertThrows(IllegalStateException.class,
				() -> new ApiKeyStartupValidator("test-api-key", false).run(new DefaultApplicationArguments()));
	}
}
