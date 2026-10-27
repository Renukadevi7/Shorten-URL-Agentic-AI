package com.agentic.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit allow/block policy outcome with audit-friendly details. */
public record PolicyDecision(String rule, boolean allowed, String reason) {

	public Map<String, Object> asMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("rule", rule);
		map.put("allowed", allowed);
		map.put("outcome", allowed ? "ALLOW" : "BLOCK");
		map.put("reason", reason);
		return map;
	}
}
