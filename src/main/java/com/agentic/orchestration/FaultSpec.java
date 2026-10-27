package com.agentic.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Generic fault injection: target a stage id with a kind (FAIL, FAIL_ONCE, …). */
public record FaultSpec(String stageId, String kind, boolean once) {

	public static FaultSpec fail(String stageId, boolean once) {
		return new FaultSpec(stageId, "FAIL", once);
	}

	public Map<String, Object> asMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("stageId", stageId);
		map.put("kind", kind);
		map.put("once", once);
		return map;
	}

	@SuppressWarnings("unchecked")
	public static FaultSpec fromContext(Object raw) {
		if (raw instanceof FaultSpec spec) {
			return spec;
		}
		if (raw instanceof Map<?, ?> map) {
			String stageId = String.valueOf(map.get("stageId"));
			Object kindObj = map.get("kind");
			String kind = kindObj == null ? "FAIL" : String.valueOf(kindObj);
			boolean once = !Boolean.FALSE.equals(map.get("once"));
			return new FaultSpec(stageId, kind, once);
		}
		return null;
	}
}
