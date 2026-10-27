package com.agentic.orchestration.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpModelClientTest {

	@Test
	void rejectsNonLocalHttpEndpoints() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> HttpModelClient.requireAllowedEndpoint("http://api.example.com/v1/draft"));
		assertTrue(ex.getMessage().toLowerCase().contains("https"));
	}

	@Test
	void allowsLocalHttpAndRemoteHttps() {
		assertDoesNotThrow(() -> HttpModelClient.requireAllowedEndpoint("http://127.0.0.1:8080/model"));
		assertDoesNotThrow(() -> HttpModelClient.requireAllowedEndpoint("https://api.example.com/v1/draft"));
	}
}
