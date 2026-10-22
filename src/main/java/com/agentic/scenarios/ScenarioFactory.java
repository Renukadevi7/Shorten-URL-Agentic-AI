package com.agentic.scenarios;

import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.StageNode;
import com.agentic.orchestration.StageType;

@Component
public class ScenarioFactory {

	public void materialize(OrchestrationRun run) {
		switch (run.scenario()) {
			case "greenfield" -> greenfield(run);
			case "brownfield" -> brownfield(run);
			case "ambiguous" -> ambiguous(run);
			default -> throw new IllegalArgumentException("Unknown scenario: " + run.scenario());
		}
	}

	/**
	 * Greenfield DAG:
	 * Requirements -> Architecture -> Implementation -> (Testing || Documentation) -> Release
	 */
	private void greenfield(OrchestrationRun run) {
		run.addStage(StageNode.from("req", StageType.REQUIREMENTS, "Normalize greenfield requirement", List.of(), false));
		run.addStage(StageNode.from("arch", StageType.ARCHITECTURE, "Draft architecture", List.of("req"), false));
		run.addStage(StageNode.from("impl", StageType.IMPLEMENTATION, "Implement create+redirect", List.of("arch"), true));
		run.addStage(StageNode.from("test", StageType.TESTING, "Validate APIs", List.of("impl"), false));
		run.addStage(StageNode.from("docs", StageType.DOCUMENTATION, "Generate API docs", List.of("impl"), false));
		run.addStage(StageNode.from("release", StageType.RELEASE_READINESS, "Release readiness gate",
				List.of("test", "docs"), true));
	}

	/**
	 * Brownfield DAG focuses on analytics/reliability enhancement of existing product.
	 */
	private void brownfield(OrchestrationRun run) {
		run.addStage(StageNode.from("req", StageType.REQUIREMENTS, "Normalize brownfield enhancement", List.of(), false));
		run.addStage(StageNode.from("arch", StageType.ARCHITECTURE, "Impact analysis for analytics", List.of("req"), false));
		run.addStage(StageNode.from("impl", StageType.IMPLEMENTATION, "Enable analytics+reliability", List.of("arch"), true));
		run.addStage(StageNode.from("test", StageType.TESTING, "Regression + stats validation", List.of("impl"), false));
		run.addStage(StageNode.from("docs", StageType.DOCUMENTATION, "Update docs for stats API", List.of("impl"), false));
		run.addStage(StageNode.from("release", StageType.RELEASE_READINESS, "Change-control release gate",
				List.of("test", "docs"), true));
	}

	/**
	 * Ambiguous DAG inserts Clarification then reuses greenfield-like flow (dynamic re-plan hook).
	 */
	private void ambiguous(OrchestrationRun run) {
		run.addStage(StageNode.from("req", StageType.REQUIREMENTS, "Detect ambiguity", List.of(), false));
		run.addStage(StageNode.from("clarify", StageType.CLARIFICATION, "Human-guided clarification / re-plan",
				List.of("req"), true));
		run.addStage(StageNode.from("arch", StageType.ARCHITECTURE, "Architecture after clarification",
				List.of("clarify"), false));
		run.addStage(StageNode.from("impl", StageType.IMPLEMENTATION, "Implement clarified scope", List.of("arch"), true));
		run.addStage(StageNode.from("test", StageType.TESTING, "Validate clarified scope", List.of("impl"), false));
		run.addStage(StageNode.from("docs", StageType.DOCUMENTATION, "Document clarified scope", List.of("impl"), false));
		run.addStage(StageNode.from("release", StageType.RELEASE_READINESS, "Release readiness",
				List.of("test", "docs"), true));
	}
}
