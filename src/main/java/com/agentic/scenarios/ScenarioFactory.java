package com.agentic.scenarios;

import org.springframework.stereotype.Component;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.StageGraph;
import com.agentic.orchestration.StageGraphLoader;

/**
 * Materializes scenario DAGs from classpath JSON configuration
 * ({@code orchestration/graphs/{scenario}.json}).
 * Adding a stage of an existing type requires only JSON edits — not OrchestratorService changes.
 */
@Component
public class ScenarioFactory {

	private final StageGraphLoader graphLoader;

	public ScenarioFactory(StageGraphLoader graphLoader) {
		this.graphLoader = graphLoader;
	}

	public StageGraph build(String scenario) {
		return graphLoader.load(scenario);
	}

	public void materialize(OrchestrationRun run) {
		StageGraph graph = build(run.scenario());
		run.context().put("stageGraph", graph.view());
		run.context().put("stageGraphSource", "classpath:orchestration/graphs/" + run.scenario() + ".json");
		graph.applyTo(run);
	}
}
