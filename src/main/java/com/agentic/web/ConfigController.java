package com.agentic.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

	private final boolean apiKeyConfigured;

	public ConfigController(@Value("${agentic.api-key:}") String apiKey) {
		this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
	}

	@GetMapping("/api/config")
	public Map<String, Object> config() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("apiKeyConfigured", apiKeyConfigured);
		map.put("authHeader", "X-API-Key");
		map.put("hint", apiKeyConfigured
				? "Use the same value as AGENTIC_API_KEY (the UI field auto-fills when bootstrap is enabled)."
				: "Set AGENTIC_API_KEY before starting the server.");
		return map;
	}
}
