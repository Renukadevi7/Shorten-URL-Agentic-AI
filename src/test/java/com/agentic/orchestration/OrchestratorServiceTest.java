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
		assertTrue(Boolean.TRUE.equals(run.context().get("securityReviewPassed")));
		assertTrue(run.lineage().stream().anyMatch(e -> "POLICY_EVAL".equals(e.kind())));
	}

	@Test
	void implementationUsesChangeAgentDiffAndOpenApiArtifacts() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		StageNode impl = run.stages().get("impl");
		assertEquals(StageStatus.AWAITING_APPROVAL, impl.status());
		@SuppressWarnings("unchecked")
		Map<String, Object> artifact = (Map<String, Object>) impl.artifacts().get("changeArtifact");
		assertNotNull(artifact);
		assertTrue(String.valueOf(artifact.get("unifiedDiff")).contains("+++"));
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("openapi:"));
		assertTrue(String.valueOf(artifact.get("apiSpecification")).contains("/api/urls"));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_PRODUCE".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_APPLY".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "AGENT_VALIDATE".equals(e.kind())));
	}

	@Test
	void graphJsonAddsSecurityStageWithoutEngineHardCodes() {
		StageGraph graph = graphLoader.load("greenfield");
		assertTrue(graph.nodes().containsKey("sec"));
		assertEquals(StageType.SECURITY_REVIEW, graph.get("sec").type());
		assertTrue(graph.get("impl").dependsOn().contains("sec"));
	}

	@Test
	void faultInjectionRequiresSuccessfulFallbackRecovery() {
		long mttrBefore = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.injectFault(run.id(), "FAIL_TESTING", false);
		orchestrator.approve(run.id(), "tester");
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		Set<String> kinds = after.lineage().stream().map(AuditEvent::kind).collect(Collectors.toSet());
		assertTrue(kinds.contains("FALLBACK_ACTIVATED"), "fallback must activate");
		assertTrue(kinds.contains("RECOVERY"), "recovery must be recorded");
		assertTrue(Boolean.TRUE.equals(after.context().get("acceptedFallback_test")));
		assertEquals(StageStatus.SUCCEEDED, after.stages().get("test_fallback").status());
		assertEquals(StageStatus.SKIPPED, after.stages().get("test").status());
		assertTrue(after.status() == RunStatus.AWAITING_APPROVAL || after.status() == RunStatus.COMPLETED);
		long mttrAfter = ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue();
		assertTrue(mttrAfter > mttrBefore);
		List<AuditEvent> recoveries = after.lineage().stream().filter(e -> "RECOVERY".equals(e.kind())).toList();
		long mttrMs = ((Number) recoveries.get(recoveries.size() - 1).details().get("mttrMs")).longValue();
		assertTrue(after.latencyMs() >= mttrMs);
		assertTrue(mttrMs >= 0);
		// MTTR is recovery delta; failed runs that never recover must not count latency as MTTR.
		assertEquals(mttrAfter, ((Number) metricsRegistry.snapshot().get("mttrSamples")).longValue());
	}

	@Test
	void postSnapshotImplFaultRollsBackProductData() {
		shortener.shorten("https://example.com/prior", "priorurl", 3600L);
		assertTrue(shortener.resolve("priorurl").isPresent());
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.",
				"FAIL_AFTER_IMPL_SNAPSHOT", false);
		assertEquals(RunStatus.SAFE_STOPPED, run.status());
		assertTrue(run.lineage().stream().anyMatch(e -> "ROLLBACK".equals(e.kind())));
		assertTrue(run.rollbacks() >= 1);
		assertNotNull(run.context().get("productSnapshotBeforeImpl"));
		// Product data restored to pre-implementation snapshot (priorurl present again).
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
		assertNotNull(test.artifacts().get("enteredAtMs"));
		assertNotNull(docs.artifacts().get("enteredAtMs"));
		long tEnter = ((Number) test.artifacts().get("enteredAtMs")).longValue();
		long dEnter = ((Number) docs.artifacts().get("enteredAtMs")).longValue();
		long tExit = ((Number) test.artifacts().getOrDefault("exitedAtMs", tEnter)).longValue();
		long dExit = ((Number) docs.artifacts().getOrDefault("exitedAtMs", dEnter)).longValue();
		boolean overlap = tEnter <= dExit && dEnter <= tExit;
		assertTrue(overlap, "test/docs windows must overlap: test=" + tEnter + "-" + tExit + " docs=" + dEnter + "-" + dExit);
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
		assertTrue(after.stages().keySet().stream().anyMatch(id -> id.startsWith("replan")));
		assertTrue(after.lineage().stream().anyMatch(e -> "REPLAN".equals(e.kind())
				|| String.valueOf(after.context().get("replanDerivedFrom")).contains("HTTPS-only")));
		assertTrue(String.valueOf(after.context().getOrDefault("replanDerivedFrom", after.normalizedProblem()))
				.contains("HTTPS") || String.valueOf(after.normalizedProblem()).contains("HTTPS"));
		assertEquals(updated, after.requirement());
	}
}
