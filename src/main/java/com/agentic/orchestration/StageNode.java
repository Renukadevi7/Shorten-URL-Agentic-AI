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
	/** When true, stage stays out of ready-set until explicitly activated (e.g. fallback). */
	private final boolean activationOnly;
	/** Context keys that must be true/present before entry. */
	private final List<String> requiredEntryContext;
	/** Context keys that must be true after exit. */
	private final List<String> requiredExitContext;
	/** Artifact keys that must exist after exit. */
	private final List<String> requiredExitArtifacts;
	/** Approving this stage completes the run (after policy checks). */
	private final boolean completesRun;
	private StageStatus status = StageStatus.BLOCKED;
	private int attempts;
	private String output;
	private final Map<String, Object> artifacts = new LinkedHashMap<>();

	public StageNode(String id, StageType type, String title, List<String> dependsOn, boolean requiresHumanApproval,
			int maxRetries, String fallbackStageId, boolean activationOnly, List<String> requiredEntryContext,
			List<String> requiredExitContext, List<String> requiredExitArtifacts, boolean completesRun) {
		this.id = id;
		this.type = type;
		this.title = title;
		this.dependsOn = List.copyOf(dependsOn);
		this.requiresHumanApproval = requiresHumanApproval;
		this.maxRetries = maxRetries;
		this.fallbackStageId = fallbackStageId;
		this.activationOnly = activationOnly;
		this.requiredEntryContext = List.copyOf(requiredEntryContext);
		this.requiredExitContext = List.copyOf(requiredExitContext);
		this.requiredExitArtifacts = List.copyOf(requiredExitArtifacts);
		this.completesRun = completesRun;
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

	public boolean activationOnly() {
		return activationOnly;
	}

	public List<String> requiredEntryContext() {
		return requiredEntryContext;
	}

	public List<String> requiredExitContext() {
		return requiredExitContext;
	}

	public List<String> requiredExitArtifacts() {
		return requiredExitArtifacts;
	}

	public boolean completesRun() {
		return completesRun;
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
		map.put("activationOnly", activationOnly);
		map.put("requiresHumanApproval", requiresHumanApproval);
		map.put("completesRun", completesRun);
		map.put("requiredEntryContext", requiredEntryContext);
		map.put("requiredExitContext", requiredExitContext);
		map.put("requiredExitArtifacts", requiredExitArtifacts);
		map.put("maxRetries", maxRetries);
		map.put("status", status.name());
		map.put("attempts", attempts);
		map.put("output", output);
		map.put("artifacts", new LinkedHashMap<>(artifacts));
		return map;
	}

	public StageNode withDependsOn(List<String> deps) {
		StageNode copy = new StageNode(id, type, title, deps, requiresHumanApproval, maxRetries, fallbackStageId,
				activationOnly, requiredEntryContext, requiredExitContext, requiredExitArtifacts, completesRun);
		copy.status = this.status;
		copy.attempts = this.attempts;
		copy.output = this.output;
		copy.artifacts.putAll(this.artifacts);
		return copy;
	}

	public static Builder builder(String id, StageType type, String title) {
		return new Builder(id, type, title);
	}

	public static final class Builder {
		private final String id;
		private final StageType type;
		private final String title;
		private List<String> dependsOn = List.of();
		private boolean requiresHumanApproval;
		private int maxRetries = 2;
		private String fallbackStageId;
		private boolean activationOnly;
		private List<String> requiredEntryContext = List.of();
		private List<String> requiredExitContext = List.of();
		private List<String> requiredExitArtifacts = List.of();
		private boolean completesRun;

		private Builder(String id, StageType type, String title) {
			this.id = id;
			this.type = type;
			this.title = title;
		}

		public Builder dependsOn(String... deps) {
			this.dependsOn = List.of(deps);
			return this;
		}

		public Builder dependsOn(List<String> deps) {
			this.dependsOn = deps == null ? List.of() : new ArrayList<>(deps);
			return this;
		}

		public Builder approval(boolean value) {
			this.requiresHumanApproval = value;
			return this;
		}

		public Builder fallback(String stageId) {
			this.fallbackStageId = stageId;
			return this;
		}

		public Builder activationOnly() {
			this.activationOnly = true;
			return this;
		}

		public Builder entryContext(String... keys) {
			this.requiredEntryContext = List.of(keys);
			return this;
		}

		public Builder exitContext(String... keys) {
			this.requiredExitContext = List.of(keys);
			return this;
		}

		public Builder exitArtifacts(String... keys) {
			this.requiredExitArtifacts = List.of(keys);
			return this;
		}

		public Builder completesRun() {
			this.completesRun = true;
			return this;
		}

		public StageNode build() {
			return new StageNode(id, type, title, dependsOn, requiresHumanApproval, maxRetries, fallbackStageId,
					activationOnly, requiredEntryContext, requiredExitContext, requiredExitArtifacts, completesRun);
		}
	}
}
