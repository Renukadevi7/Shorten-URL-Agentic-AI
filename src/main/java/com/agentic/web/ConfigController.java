package com.agentic.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated config probe. Does not reveal whether a key is set beyond what
 * the caller already proved by authenticating.
 */
@RestController
public class ConfigController {

	@GetMapping("/api/config")
	public Map<String, Object> config() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("authHeader", "X-API-Key");
		map.put("ok", true);
		return map;
	}
}
