package com.agentic.orchestration.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-produced engineering change: unified diff + API specification + apply operations.
 */
public record ChangeArtifact(
		String changeId,
		String summary,
		String rationale,
		List<String> filesTouched,
		String unifiedDiff,
		String apiSpecification,
		Map<String, Object> operations,
		Map<String, Object> validationPlan
) {
	public Map<String, Object> asMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("changeId", changeId);
		map.put("summary", summary);
		map.put("rationale", rationale);
		map.put("filesTouched", filesTouched);
		map.put("unifiedDiff", unifiedDiff);
		map.put("apiSpecification", apiSpecification);
		map.put("operations", operations);
		map.put("validationPlan", validationPlan);
		return map;
	}
}
