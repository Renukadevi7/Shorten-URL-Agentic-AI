package com.agentic.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyStartupValidator implements ApplicationRunner {

	private final String apiKey;
	private final boolean allowBlank;

	public ApiKeyStartupValidator(
			@Value("${agentic.api-key:}") String apiKey,
			@Value("${agentic.api-key.allow-blank:false}") boolean allowBlank) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.allowBlank = allowBlank;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (apiKey.isBlank()) {
			if (allowBlank) {
				return;
			}
			throw new IllegalStateException(
					"agentic.api-key is blank. Set AGENTIC_API_KEY before starting the application.");
		}
		if (isWeak(apiKey)) {
			throw new IllegalStateException(
					"agentic.api-key is too weak. Use at least 16 characters and avoid placeholders.");
		}
	}

	static boolean isWeak(String key) {
		if (key == null || key.length() < 16) {
			return true;
		}
		String lower = key.toLowerCase();
		return lower.contains("change-me") || lower.equals("test-api-key") || lower.equals("password")
				|| lower.equals("demo-api-key-change-me");
	}
}
