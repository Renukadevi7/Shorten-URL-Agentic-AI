package com.agentic.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StageNode {

	private final String id;
	private final StageType type;
	private final String title;
	private final List<String> dependsOn;
	private final boolean requiresHumanApproval;
	private final int maxRetries;
	private final String fallbackStageId;
	private StageStatus status = StageStatus.BLOCKED;
	private int attempts;
	private String output;
	private final Map<String, Object> artifacts = new LinkedHashMap<>();

	public StageNode(String id, StageType type, String title, List<String> dependsOn, boolean requiresHumanApproval,
			int maxRetries, String fallbackStageId) {
		this.id = id;
		this.type = type;
		this.title = title;
		this.dependsOn = List.copyOf(dependsOn);
		this.requiresHumanApproval = requiresHumanApproval;
		this.maxRetries = maxRetries;
		this.fallbackStageId = fallbackStageId;
	}

	public String id() {
		return id;
	}

	public StageType type() {
		return type;
	}

	public String title() {
		return title;
	}

	public List<String> dependsOn() {
		return dependsOn;
	}

	public boolean requiresHumanApproval() {
		return requiresHumanApproval;
	}

	public int maxRetries() {
		return maxRetries;
	}

	public String fallbackStageId() {
		return fallbackStageId;
	}

	public StageStatus status() {
		return status;
	}

	public void setStatus(StageStatus status) {
		this.status = status;
	}

	public int attempts() {
		return attempts;
	}

	public void incrementAttempts() {
		attempts++;
	}

	public void resetAttempts() {
		attempts = 0;
	}

	public String output() {
		return output;
	}

	public void setOutput(String output) {
		this.output = output;
	}

	public Map<String, Object> artifacts() {
		return artifacts;
	}

	public Map<String, Object> snapshot() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		map.put("type", type.name());
		map.put("title", title);
		map.put("dependsOn", dependsOn);
		map.put("fallbackStageId", fallbackStageId);
		map.put("requiresHumanApproval", requiresHumanApproval);
		map.put("maxRetries", maxRetries);
		map.put("status", status.name());
		map.put("attempts", attempts);
		map.put("output", output);
		map.put("artifacts", new LinkedHashMap<>(artifacts));
		return map;
	}

	public static StageNode of(String id, StageType type, String title, List<String> deps, boolean approval,
			String fallback) {
		return new StageNode(id, type, title, deps == null ? List.of() : new ArrayList<>(deps), approval, 2, fallback);
	}

	public static StageNode of(String id, StageType type, String title, List<String> deps, boolean approval) {
		return of(id, type, title, deps, approval, null);
	}
}
