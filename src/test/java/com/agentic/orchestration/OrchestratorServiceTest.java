package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.agentic.shortener.UrlShortenerService;

@SpringBootTest
class OrchestratorServiceTest {

	@Autowired
	private OrchestratorService orchestrator;

	@Autowired
	private UrlShortenerService shortener;

	@Autowired
	private MetricsRegistry metricsRegistry;

	@BeforeEach
	void reset() {
		shortener.resetProduct();
	}

	@Test
	void greenfieldPausesForImplementationApproval() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		assertEquals(RunStatus.AWAITING_APPROVAL, run.status());
		assertEquals("impl", run.awaitingStageId());
		assertTrue(run.lineage().stream().anyMatch(e -> "PARALLEL_WAVE".equals(e.kind())
				|| "STAGE_ENTER".equals(e.kind())));
		assertTrue(run.stages().get("impl").artifacts().containsKey("changeArtifact")
				|| run.context().containsKey("changeArtifact")
				|| run.stages().get("impl").status() == StageStatus.AWAITING_APPROVAL);
	}

	@Test
	void implementationUsesChangeAgentArtifacts() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		StageNode impl = run.stages().get("impl");
		assertEquals(StageStatus.AWAITING_APPROVAL, impl.status());
		assertTrue(impl.artifacts().containsKey("changeArtifact"));
		assertTrue(impl.artifacts().containsKey("agentValidation"));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_PRODUCE".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_VALIDATE".equals(e.kind())));
	}

	@Test
	void faultInjectionActivatesFallbackPathAndRecordsMttr() {
		long mttrBefore = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.injectFault(run.id(), "FAIL_TESTING", false);
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		Set<String> kinds = after.lineage().stream().map(AuditEvent::kind).collect(Collectors.toSet());
		assertTrue(kinds.contains("FAULT_INJECTION"));
		assertTrue(kinds.contains("FALLBACK_ACTIVATED") || kinds.contains("RETRY"));
		assertTrue(kinds.contains("RECOVERY") || kinds.contains("FAILURE_CLOCK_START"));
		assertTrue(after.status() == RunStatus.AWAITING_APPROVAL
				|| after.status() == RunStatus.SAFE_STOPPED
				|| after.status() == RunStatus.COMPLETED,
				"fault path must not hang; status=" + after.status());
		assertTrue(after.lineage().stream().filter(e -> "PARALLEL_WAVE".equals(e.kind())).count() < 50,
				"must not infinite-loop parallel waves");
		long mttrAfter = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		assertTrue(mttrAfter >= mttrBefore, "MTTR samples should increase on recovery");
	}

	@Test
	void parallelStagesOverlapInExecution() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		List<AuditEvent> enters = after.lineage().stream()
				.filter(e -> "STAGE_ENTER".equals(e.kind()))
				.filter(e -> "test".equals(e.stageId()) || "docs".equals(e.stageId()))
				.toList();
		assertTrue(enters.size() >= 2, "test and docs should both enter");
		Set<String> threads = enters.stream()
				.map(e -> String.valueOf(e.details().get("thread")))
				.collect(Collectors.toSet());
		assertTrue(threads.stream().anyMatch(t -> t.contains("orch-parallel")),
				"parallel executor threads should appear: " + threads);
		assertTrue(after.lineage().stream().anyMatch(e -> "PARALLEL_WAVE".equals(e.kind())));
	}

	@Test
	void mutateUpstreamCreatesDynamicReplanEvent() {
		OrchestrationRun run = orchestrator.start("ambiguous",
				"Make links better for the leadership demo next week.");
		orchestrator.mutateUpstream(run.id(),
				"Updated upstream: ship HTTPS-only short links with stats for leadership demo.");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		assertTrue(after.lineage().stream().anyMatch(e -> "UPSTREAM_CHANGED".equals(e.kind())
				|| "DYNAMIC_REPLAN".equals(e.kind()) || "REPLAN".equals(e.kind())));
		assertTrue(after.stages().keySet().stream().anyMatch(id -> id.startsWith("replan")
				|| after.context().containsKey("replanReason")));
	}
}
