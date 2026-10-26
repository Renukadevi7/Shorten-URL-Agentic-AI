package com.agentic.shortener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time product state used for real rollback.
 */
public record ProductSnapshot(
		boolean analyticsEnabled,
		boolean reliabilityEnabled,
		List<Map<String, Object>> urls
) {
	public Map<String, Object> asMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("analyticsEnabled", analyticsEnabled);
		map.put("reliabilityEnabled", reliabilityEnabled);
		map.put("urlCount", urls.size());
		map.put("urls", urls);
		return map;
	}
}
