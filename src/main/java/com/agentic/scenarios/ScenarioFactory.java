package com.agentic.scenarios;

import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.StageGraph;
import com.agentic.orchestration.StageNode;
import com.agentic.orchestration.StageType;

@Component
public class ScenarioFactory {

	public StageGraph build(String scenario) {
		return switch (scenario) {
			case "greenfield" -> greenfield();
			case "brownfield" -> brownfield();
			case "ambiguous" -> ambiguous();
			default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
		};
	}

	public void materialize(OrchestrationRun run) {
		StageGraph graph = build(run.scenario());
		run.context().put("stageGraph", graph.view());
		graph.applyTo(run);
	}

	/**
	 * Greenfield DAG with parallel Testing ∥ Documentation after Implementation,
	 * and Testing fallback edge for fault-injection demos.
	 */
	private StageGraph greenfield() {
		StageGraph g = new StageGraph();
		g.add(StageNode.of("req", StageType.REQUIREMENTS, "Normalize greenfield requirement", List.of(), false));
		g.add(StageNode.of("arch", StageType.ARCHITECTURE, "Draft architecture", List.of("req"), false));
		g.add(StageNode.of("impl", StageType.IMPLEMENTATION, "Implement create+redirect+reliability APIs",
				List.of("arch"), true));
		g.add(StageNode.of("test", StageType.TESTING, "Validate APIs (primary)", List.of("impl"), false,
				"test_fallback"));
		g.add(StageNode.of("test_fallback", StageType.TESTING_FALLBACK, "Fallback reduced test pack",
				List.of("impl"), false));
		g.add(StageNode.of("docs", StageType.DOCUMENTATION, "Generate API docs", List.of("impl"), false));
		g.add(StageNode.of("release", StageType.RELEASE_READINESS, "Release readiness gate",
				List.of("test", "docs"), true));
		return g;
	}

	private StageGraph brownfield() {
		StageGraph g = new StageGraph();
		g.add(StageNode.of("req", StageType.REQUIREMENTS, "Normalize brownfield enhancement", List.of(), false));
		g.add(StageNode.of("arch", StageType.ARCHITECTURE, "Impact analysis for analytics/reliability",
				List.of("req"), false));
		g.add(StageNode.of("impl", StageType.IMPLEMENTATION, "Enable analytics+reliability + persistence checks",
				List.of("arch"), true));
		g.add(StageNode.of("test", StageType.TESTING, "Regression + stats validation", List.of("impl"), false,
				"test_fallback"));
		g.add(StageNode.of("test_fallback", StageType.TESTING_FALLBACK, "Fallback smoke tests", List.of("impl"),
				false));
		g.add(StageNode.of("docs", StageType.DOCUMENTATION, "Update docs for stats/delete/alias", List.of("impl"),
				false));
		g.add(StageNode.of("release", StageType.RELEASE_READINESS, "Change-control release gate",
				List.of("test", "docs"), true));
		return g;
	}

	/**
	 * Ambiguous starts like greenfield; clarification is NOT pre-baked as the replan mechanism.
	 * Dynamic replan inserts REPLAN when upstream outputs change at runtime.
	 */
	private StageGraph ambiguous() {
		StageGraph g = new StageGraph();
		g.add(StageNode.of("req", StageType.REQUIREMENTS, "Detect ambiguity in requirement", List.of(), false));
		g.add(StageNode.of("arch", StageType.ARCHITECTURE, "Architecture for provisional scope", List.of("req"),
				false));
		g.add(StageNode.of("impl", StageType.IMPLEMENTATION, "Implement provisional scope", List.of("arch"), true));
		g.add(StageNode.of("test", StageType.TESTING, "Validate provisional scope", List.of("impl"), false,
				"test_fallback"));
		g.add(StageNode.of("test_fallback", StageType.TESTING_FALLBACK, "Fallback smoke tests", List.of("impl"),
				false));
		g.add(StageNode.of("docs", StageType.DOCUMENTATION, "Document provisional scope", List.of("impl"), false));
		g.add(StageNode.of("release", StageType.RELEASE_READINESS, "Release readiness", List.of("test", "docs"),
				true));
		return g;
	}
}
