package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	}

	@Test
	void faultInjectionActivatesFallbackPath() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");
		orchestrator.injectFault(run.id(), "FAIL_TESTING", false); // sticky fault forces fallback
		orchestrator.approve(run.id(), "tester"); // impl approval continues into test/docs
		OrchestrationRun after = orchestrator.get(run.id()).orElseThrow();
		Set<String> kinds = after.lineage().stream().map(AuditEvent::kind).collect(Collectors.toSet());
		assertTrue(kinds.contains("FAULT_INJECTION"));
		assertTrue(kinds.contains("FALLBACK_ACTIVATED") || kinds.contains("RETRY"));
		assertTrue(after.status() == RunStatus.AWAITING_APPROVAL
				|| after.status() == RunStatus.SAFE_STOPPED
				|| after.status() == RunStatus.COMPLETED,
				"fault path must not hang; status=" + after.status());
		assertTrue(after.lineage().stream().filter(e -> "PARALLEL_WAVE".equals(e.kind())).count() < 50,
				"must not infinite-loop parallel waves");
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
