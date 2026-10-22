package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.agentic.scenarios.ScenarioFactory;
import com.agentic.shortener.UrlShortenerService;

class OrchestratorServiceTest {

	@Test
	void greenfieldPausesForImplementationApproval() {
		UrlShortenerService shortener = new UrlShortenerService();
		OrchestratorService orchestrator = new OrchestratorService(
				new ScenarioFactory(),
				new StageExecutor(shortener),
				new PolicyGuardrail(),
				new MetricsRegistry());

		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs.");

		assertEquals(RunStatus.AWAITING_APPROVAL, run.status());
		assertEquals("impl", run.awaitingStageId());

		orchestrator.approve(run.id(), "tester");
		assertTrue(run.status() == RunStatus.AWAITING_APPROVAL || run.status() == RunStatus.COMPLETED
				|| run.status() == RunStatus.RUNNING);
	}
}
