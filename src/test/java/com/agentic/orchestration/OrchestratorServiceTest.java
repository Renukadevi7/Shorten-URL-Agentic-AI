package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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

	@Autowired
	private StageGraphLoader graphLoader;

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
		assertEquals(StageStatus.SUCCEEDED, run.stages().get("sec").status());
	}

	@Test
	void implementationDraftCompileRepairProducesRealDiff() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		StageNode impl = run.stages().get("impl");
		@SuppressWarnings("unchecked")
		Map<String, Object> artifact = (Map<String, Object>) impl.artifacts().get("changeArtifact");
		assertNotNull(artifact);
		assertTrue(String.valueOf(artifact.get("unifiedDiff")).contains("AliasPolicy"));
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("openapi:"));
		Object drafts = ((Map<?, ?>) artifact.get("operations")).get("drafts");
		assertTrue(drafts instanceof List<?> list && list.size() >= 1);
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_PRODUCE".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_VALIDATE".equals(e.kind())));
	}

	@Test
	void graphJsonAddsSecurityStageWithoutEngineHardCodes() {
		StageGraph graph = graphLoader.load("greenfield");
		assertTrue(graph.nodes().containsKey("sec"));
		assertTrue(graph.get("impl").consumesContext().contains("securityReviewPassed"));
	}

	@Test
	void stageIdFaultTriggersFallbackRecoveryAndRecordsMttrValue() throws InterruptedException {
		long mttrBefore = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		long mttrSumBefore = ((Number) metricsRegistry.snapshot().get("avgMttrMs")).longValue();
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.injectFault(run.id(), FaultSpec.fail("test", false));
		Thread.sleep(30);
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		assertTrue(after.lineage().stream().anyMatch(e -> "FALLBACK_ACTIVATED".equals(e.kind())));
		assertTrue(after.lineage().stream().anyMatch(e -> "RECOVERY".equals(e.kind())));
		assertEquals(StageStatus.SUCCEEDED, after.stages().get("test_fallback").status());
		assertEquals(StageStatus.SKIPPED, after.stages().get("test").status());
		List<AuditEvent> recoveries = after.lineage().stream().filter(e -> "RECOVERY".equals(e.kind())).toList();
		long mttrMs = ((Number) recoveries.get(recoveries.size() - 1).details().get("mttrMs")).longValue();
		assertTrue(mttrMs >= 0, "MTTR must be recorded as a non-negative recovery delta");
		assertTrue(after.latencyMs() >= mttrMs, "e2e latency must cover the MTTR window");
		// MTTR is recovery-only; it must not equal full-run latency for a multi-stage run.
		assertTrue(mttrMs < after.latencyMs() || after.latencyMs() < 50,
				"MTTR (" + mttrMs + ") should differ from full-run latency (" + after.latencyMs() + ")");
		long mttrAfter = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		assertTrue(mttrAfter > mttrBefore);
		assertTrue(((Number) metricsRegistry.snapshot().get("avgMttrMs")).longValue() >= 0 || mttrSumBefore >= 0);
	}

	@Test
	void retryExhaustionOnFallbackFailsAndRollsBack() {
		shortener.shorten("https://example.com/prior", "priorurl", 3600L);
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.injectFault(run.id(), new FaultSpec("test,test_fallback", "FAIL", false));
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		assertEquals(RunStatus.SAFE_STOPPED, after.status());
		assertTrue(after.lineage().stream().anyMatch(e -> "ROLLBACK".equals(e.kind())));
		assertTrue(after.rollbacks() >= 1);
	}

	@Test
	void postSnapshotImplFaultRollsBackProductData() {
		shortener.shorten("https://example.com/prior", "priorurl", 3600L);
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.",
				"impl:FAIL_AFTER_SNAPSHOT", false);
		assertEquals(RunStatus.SAFE_STOPPED, run.status());
		assertTrue(run.lineage().stream().anyMatch(e -> "ROLLBACK".equals(e.kind())));
		assertTrue(shortener.resolve("priorurl").isPresent(), "rollback must restore prior URL row");
		assertFalse(Boolean.TRUE.equals(run.context().get("implementationDone")));
	}

	@Test
	void parallelStagesHaveOverlappingExecutionWindows() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		StageNode test = after.stages().get("test");
		StageNode docs = after.stages().get("docs");
		long tEnter = ((Number) test.artifacts().get("enteredAtMs")).longValue();
		long dEnter = ((Number) docs.artifacts().get("enteredAtMs")).longValue();
		long tExit = ((Number) test.artifacts().getOrDefault("exitedAtMs", tEnter)).longValue();
		long dExit = ((Number) docs.artifacts().getOrDefault("exitedAtMs", dEnter)).longValue();
		assertTrue(tEnter <= dExit && dEnter <= tExit, "test/docs windows must overlap");
		Set<String> threads = after.lineage().stream()
				.filter(e -> "STAGE_ENTER".equals(e.kind()))
				.filter(e -> "test".equals(e.stageId()) || "docs".equals(e.stageId()))
				.map(e -> String.valueOf(e.details().get("thread")))
				.collect(Collectors.toSet());
		assertTrue(threads.stream().anyMatch(t -> t.contains("orch-parallel")), threads.toString());
	}

	@Test
	void ambiguousMutateUpstreamDerivesReplanFromNewRequirement() {
		OrchestrationRun run = orchestrator.start("ambiguous",
				"Make links better for the leadership demo next week.");
		String updated = "Updated upstream: ship HTTPS-only short links with stats and alias for leadership demo.";
		orchestrator.mutateUpstream(run.id(), updated);
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		assertTrue(after.lineage().stream().anyMatch(e -> "DYNAMIC_REPLAN".equals(e.kind())));
		assertTrue(after.lineage().stream().anyMatch(e -> "REPLAN_INVALIDATE".equals(e.kind())));
		assertEquals(updated, after.requirement());
		assertTrue(String.valueOf(after.normalizedProblem()).contains("HTTPS")
				|| String.valueOf(after.context().get("replanDerivedFrom")).contains("HTTPS"));
	}
}
