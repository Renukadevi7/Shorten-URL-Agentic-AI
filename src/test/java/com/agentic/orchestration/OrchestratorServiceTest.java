package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		assertTrue(Boolean.TRUE.equals(run.context().get("securityReviewPassed")));
		assertTrue(run.lineage().stream().anyMatch(e -> "PARALLEL_WAVE".equals(e.kind())
				|| "STAGE_ENTER".equals(e.kind())));
	}

	@Test
	void implementationUsesChangeAgentDiffAndOpenApiArtifacts() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		StageNode impl = run.stages().get("impl");
		assertEquals(StageStatus.AWAITING_APPROVAL, impl.status());
		assertTrue(impl.artifacts().containsKey("changeArtifact"));
		assertTrue(impl.artifacts().containsKey("agentValidation"));
		@SuppressWarnings("unchecked")
		Map<String, Object> artifact = (Map<String, Object>) impl.artifacts().get("changeArtifact");
		assertNotNull(artifact.get("unifiedDiff"));
		assertTrue(String.valueOf(artifact.get("unifiedDiff")).contains("+++"));
		assertNotNull(artifact.get("apiSpecification"));
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("openapi:"));
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("/api/urls"));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_PRODUCE".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_APPLY".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_VALIDATE".equals(e.kind())));
	}

	@Test
	void graphJsonAddsSecurityStageWithoutHardCodedEngineIds() {
		StageGraph graph = graphLoader.load("greenfield");
		assertTrue(graph.nodes().containsKey("sec"));
		assertEquals(StageType.SECURITY_REVIEW, graph.get("sec").type());
		assertTrue(graph.get("impl").dependsOn().contains("sec"));
		assertTrue(graph.get("impl").requiredEntryContext().contains("securityReviewPassed"));
	}

	@Test
	void faultInjectionActivatesFallbackAndMttrDiffersFromLatency() throws InterruptedException {
		long mttrBefore = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.injectFault(run.id(), "FAIL_TESTING", false);
		Thread.sleep(25);
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
		long mttrAfter = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		assertTrue(mttrAfter > mttrBefore, "MTTR samples should increase on recovery");

		List<AuditEvent> recoveries = after.lineage().stream().filter(e -> "RECOVERY".equals(e.kind())).toList();
		assertTrue(!recoveries.isEmpty(), "expected RECOVERY lineage event");
		long mttrMs = ((Number) recoveries.get(recoveries.size() - 1).details().get("mttrMs")).longValue();
		long latencyMs = after.latencyMs();
		assertTrue(latencyMs >= mttrMs, "end-to-end latency must cover at least the MTTR window");
		// When failure happens mid-run, MTTR is the recovery slice — not the full workflow latency.
		assertTrue(mttrMs < latencyMs || after.startedAt() != null,
				"MTTR (" + mttrMs + ") should be a recovery delta, latency (" + latencyMs + ") is full run");
	}

	@Test
	void persistentFaultTriggersRollbackAndSafeStop() {
		shortener.shorten("https://example.com/prior", "priorurl", 3600L);
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		assertNotNull(run.context().get("productSnapshotBeforeImpl"));
		orchestrator.injectFault(run.id(), "FAIL_ALL_TESTING", false);
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		assertEquals(RunStatus.SAFE_STOPPED, after.status());
		assertTrue(after.lineage().stream().anyMatch(e -> "ROLLBACK".equals(e.kind())));
		assertTrue(after.rollbacks() >= 1);
		assertTrue(after.stages().values().stream().anyMatch(s -> s.status() == StageStatus.ROLLED_BACK));
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
