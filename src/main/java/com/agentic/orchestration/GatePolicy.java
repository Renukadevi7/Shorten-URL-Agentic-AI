package com.agentic.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Entry/exit gates that validate dependency and stage *outputs*, not only status flags.
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
			if (dep.status() != StageStatus.SUCCEEDED && dep.status() != StageStatus.SKIPPED) {
				violations.add("Dependency not succeeded: " + depId + " status=" + dep.status());
				continue;
			}
			violations.addAll(validateDependencyOutput(dep, stage));
		}

		if (stage.type() == StageType.IMPLEMENTATION
				&& !Boolean.TRUE.equals(run.context().get("architectureReady"))) {
			violations.add("Architecture output missing: architectureReady != true");
		}
		if (stage.type() == StageType.RELEASE_READINESS
				&& !Boolean.TRUE.equals(run.context().get("testsPassed"))) {
			violations.add("Cannot enter release: testsPassed != true");
		}
		if (stage.type() == StageType.RELEASE_READINESS
				&& !Boolean.TRUE.equals(run.context().get("docsReady"))) {
			violations.add("Cannot enter release: docsReady != true");
		}
		return violations;
	}

	public List<String> exitViolations(OrchestrationRun run, StageNode stage) {
		List<String> violations = new ArrayList<>();
		if (stage.output() == null || stage.output().isBlank()) {
			violations.add("Stage produced empty output");
		}
		switch (stage.type()) {
			case REQUIREMENTS -> {
				if (run.normalizedProblem() == null || run.normalizedProblem().isBlank()) {
					violations.add("Requirements exit requires normalizedProblem");
				}
			}
			case ARCHITECTURE -> {
				if (!stage.artifacts().containsKey("architecture")) {
					violations.add("Architecture artifact missing");
				}
				if (!Boolean.TRUE.equals(run.context().get("architectureReady"))) {
					violations.add("architectureReady context flag missing");
				}
			}
			case IMPLEMENTATION -> {
				if (!Boolean.TRUE.equals(run.context().get("implementationDone"))) {
					violations.add("implementationDone context flag missing");
				}
				if (run.context().get("productSnapshotBeforeImpl") == null) {
					violations.add("Rollback snapshot was not captured before implementation");
				}
			}
			case TESTING, TESTING_FALLBACK -> {
				if (!Boolean.TRUE.equals(run.context().get("testsPassed"))) {
					violations.add("testsPassed must be true to exit testing");
				}
				if (!stage.artifacts().containsKey("testReport")) {
					violations.add("testReport artifact missing");
				}
				if (!Boolean.TRUE.equals(stage.artifacts().get("passed"))
						&& !Boolean.TRUE.equals(stage.artifacts().get("testPassed"))) {
					violations.add("testing artifact must record passed=true");
				}
			}
			case DOCUMENTATION -> {
				if (!Boolean.TRUE.equals(run.context().get("docsReady"))) {
					violations.add("docsReady context flag missing");
				}
			}
			case RELEASE_READINESS -> violations.addAll(policyGuardrail.validateRelease(run));
			case CLARIFICATION, REPLAN -> {
				if (!stage.artifacts().containsKey("replanReason") && !stage.artifacts().containsKey("clarifiedRequirement")) {
					violations.add("Replan/clarification artifact missing");
				}
			}
			default -> {
			}
		}
		return violations;
	}

	private List<String> validateDependencyOutput(StageNode dep, StageNode consumer) {
		List<String> violations = new ArrayList<>();
		if (dep.type() == StageType.TESTING && consumer.type() == StageType.RELEASE_READINESS) {
			// Primary test may be SKIPPED after a successful fallback path.
			if (dep.status() == StageStatus.SKIPPED) {
				return violations;
			}
			Object passed = dep.artifacts().get("passed");
			if (!Boolean.TRUE.equals(passed) && !Boolean.TRUE.equals(dep.artifacts().get("testPassed"))) {
				// also accept context-driven runs where artifact uses testReport only
				if (!String.valueOf(dep.output()).toLowerCase().contains("passed")) {
					violations.add("Testing dependency output does not confirm pass");
				}
			}
		}
		if (dep.type() == StageType.ARCHITECTURE && consumer.type() == StageType.IMPLEMENTATION) {
			Object arch = dep.artifacts().get("architecture");
			if (!(arch instanceof Map<?, ?> map) || map.isEmpty()) {
				violations.add("Architecture dependency missing architecture artifact map");
			}
		}
		return violations;
	}
}
