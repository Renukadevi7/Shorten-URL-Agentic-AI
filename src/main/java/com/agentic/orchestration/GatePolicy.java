package com.agentic.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Entry/exit gates driven by stage metadata (required context/artifacts), not hard-coded stage ids.
 */
@Component
public class GatePolicy {

	private final PolicyGuardrail policyGuardrail;

	public GatePolicy(PolicyGuardrail policyGuardrail) {
		this.policyGuardrail = policyGuardrail;
	}

	public List<String> entryViolations(OrchestrationRun run, StageNode stage) {
		List<String> violations = new ArrayList<>();
		for (String depId : stage.dependsOn()) {
			StageNode dep = run.stages().get(depId);
			if (dep == null) {
				violations.add("Missing dependency stage: " + depId);
				continue;
			}
			if (!dependencySatisfied(run, dep)) {
				violations.add("Dependency not satisfied: " + depId + " status=" + dep.status());
				continue;
			}
			violations.addAll(validateDependencyArtifacts(dep));
		}
		for (String key : stage.requiredEntryContext()) {
			if (!Boolean.TRUE.equals(run.context().get(key))) {
				violations.add("Entry context missing/false: " + key);
			}
		}
		return violations;
	}

	public List<String> exitViolations(OrchestrationRun run, StageNode stage) {
		List<String> violations = new ArrayList<>();
		if (stage.output() == null || stage.output().isBlank()) {
			violations.add("Stage produced empty output");
		}
		for (String key : stage.requiredExitContext()) {
			if (!Boolean.TRUE.equals(run.context().get(key))) {
				violations.add("Exit context missing/false: " + key);
			}
		}
		for (String key : stage.requiredExitArtifacts()) {
			if (!stage.artifacts().containsKey(key)) {
				violations.add("Exit artifact missing: " + key);
			}
		}
		if (stage.completesRun()) {
			violations.addAll(policyGuardrail.validateRelease(run));
		}
		// Generic pass marker when stage declares a "passed" artifact requirement.
		if (stage.requiredExitArtifacts().contains("passed") || stage.requiredExitArtifacts().contains("testPassed")) {
			if (!Boolean.TRUE.equals(stage.artifacts().get("passed"))
					&& !Boolean.TRUE.equals(stage.artifacts().get("testPassed"))) {
				violations.add("Stage must record passed=true");
			}
		}
		return violations;
	}

	private boolean dependencySatisfied(OrchestrationRun run, StageNode dep) {
		if (dep.status() == StageStatus.SUCCEEDED || dep.status() == StageStatus.SKIPPED) {
			return true;
		}
		// Fallback acceptance is recorded generically as acceptedFallback_<stageId>
		return Boolean.TRUE.equals(run.context().get("acceptedFallback_" + dep.id()));
	}

	private List<String> validateDependencyArtifacts(StageNode dep) {
		List<String> violations = new ArrayList<>();
		if (dep.status() == StageStatus.SKIPPED) {
			return violations;
		}
		for (String key : dep.requiredExitArtifacts()) {
			if (!dep.artifacts().containsKey(key)
					&& !(key.equals("passed") && Boolean.TRUE.equals(dep.artifacts().get("testPassed")))
					&& !(key.equals("testPassed") && Boolean.TRUE.equals(dep.artifacts().get("passed")))) {
				// Soft check: dependency should have produced declared exit artifacts when succeeded
				if (dep.status() == StageStatus.SUCCEEDED && dep.artifacts().isEmpty()) {
					violations.add("Dependency " + dep.id() + " missing expected artifact " + key);
				}
			}
		}
		Object arch = dep.artifacts().get("architecture");
		if (dep.requiredExitArtifacts().contains("architecture")
				&& (!(arch instanceof Map<?, ?> map) || map.isEmpty())) {
			violations.add("Dependency " + dep.id() + " missing architecture artifact map");
		}
		return violations;
	}
}
