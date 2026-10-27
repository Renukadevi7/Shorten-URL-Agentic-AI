package com.agentic.orchestration.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-produced change proposal that can be applied and validated independently of stage scripting.
 */
public record ChangeArtifact(
		String changeId,
		String summary,
		String rationale,
		List<String> filesTouched,
		Map<String, Object> operations,
		Map<String, Object> validationPlan
) {
	public Map<String, Object> asMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("changeId", changeId);
		map.put("summary", summary);
		map.put("rationale", rationale);
		map.put("filesTouched", filesTouched);
		map.put("operations", operations);
		map.put("validationPlan", validationPlan);
		return map;
	}
}
