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

	private StageGraph greenfield() {
		StageGraph g = new StageGraph();
		g.add(req("Normalize greenfield requirement"));
		g.add(arch("Draft architecture", "req"));
		g.add(impl("Implement create+redirect+reliability APIs", "arch"));
		g.add(test("Validate APIs (primary)", "impl", "test_fallback"));
		g.add(testFallback("Fallback reduced test pack", "impl"));
		g.add(docs("Generate API docs", "impl"));
		g.add(release("Release readiness gate", "test", "docs"));
		return g;
	}

	private StageGraph brownfield() {
		StageGraph g = new StageGraph();
		g.add(req("Normalize brownfield enhancement"));
		g.add(arch("Impact analysis for analytics/reliability", "req"));
		g.add(impl("Enable analytics+reliability + persistence checks", "arch"));
		g.add(test("Regression + stats validation", "impl", "test_fallback"));
		g.add(testFallback("Fallback smoke tests", "impl"));
		g.add(docs("Update docs for stats/delete/alias", "impl"));
		g.add(release("Change-control release gate", "test", "docs"));
		return g;
	}

	private StageGraph ambiguous() {
		StageGraph g = new StageGraph();
		g.add(req("Detect ambiguity in requirement"));
		g.add(arch("Architecture for provisional scope", "req"));
		g.add(impl("Implement provisional scope", "arch"));
		g.add(test("Validate provisional scope", "impl", "test_fallback"));
		g.add(testFallback("Fallback smoke tests", "impl"));
		g.add(docs("Document provisional scope", "impl"));
		g.add(release("Release readiness", "test", "docs"));
		return g;
	}

	private static StageNode req(String title) {
		return StageNode.builder("req", StageType.REQUIREMENTS, title)
				.exitArtifacts("problemStatement", "impactAnalysis")
				.build();
	}

	private static StageNode arch(String title, String dep) {
		return StageNode.builder("arch", StageType.ARCHITECTURE, title)
				.dependsOn(dep)
				.exitContext("architectureReady")
				.exitArtifacts("architecture")
				.build();
	}

	private static StageNode impl(String title, String dep) {
		return StageNode.builder("impl", StageType.IMPLEMENTATION, title)
				.dependsOn(dep)
				.approval(true)
				.entryContext("architectureReady")
				.exitContext("implementationDone")
				.exitArtifacts("changeArtifact", "agentValidation")
				.build();
	}

	private static StageNode test(String title, String dep, String fallback) {
		return StageNode.builder("test", StageType.TESTING, title)
				.dependsOn(dep)
				.fallback(fallback)
				.exitContext("testsPassed")
				.exitArtifacts("testReport", "passed")
				.build();
	}

	private static StageNode testFallback(String title, String dep) {
		return StageNode.builder("test_fallback", StageType.TESTING_FALLBACK, title)
				.dependsOn(dep)
				.activationOnly()
				.exitContext("testsPassed")
				.exitArtifacts("testReport", "passed")
				.build();
	}

	private static StageNode docs(String title, String dep) {
		return StageNode.builder("docs", StageType.DOCUMENTATION, title)
				.dependsOn(dep)
				.exitContext("docsReady")
				.exitArtifacts("documentation")
				.build();
	}

	private static StageNode release(String title, String... deps) {
		return StageNode.builder("release", StageType.RELEASE_READINESS, title)
				.dependsOn(List.of(deps))
				.approval(true)
				.entryContext("testsPassed", "docsReady")
				.exitArtifacts("releaseChecklist")
				.completesRun()
				.build();
	}
}
