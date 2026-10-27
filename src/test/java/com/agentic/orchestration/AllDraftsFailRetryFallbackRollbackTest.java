package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.agentic.shortener.AliasRulesHolder;
import com.agentic.shortener.UrlShortenerService;

/**
 * When every model draft fails: orchestrator retries impl, activates impl_fallback,
 * and when that also fails, rolls back product state and safe-stops.
 */
@SpringBootTest(properties = {
		"agentic.api-key=local-test-key-16c",
		"agentic.model.force-fail=true"
})
class AllDraftsFailRetryFallbackRollbackTest {

	@Autowired
	private OrchestratorService orchestrator;

	@Autowired
	private UrlShortenerService shortener;

	@Autowired
	private AliasRulesHolder aliasRulesHolder;

	@BeforeEach
	void reset() {
		shortener.resetProduct();
		aliasRulesHolder.reset();
		shortener.shorten("https://example.com/prior", "priorurl", 3600L);
	}

	@Test
	void allThreeDraftsFailTriggersRetryFallbackAndRollback() {
		OrchestrationRun run = orchestrator.start("greenfield",
				"Build a URL shortener with create and redirect APIs over HTTPS.");

		assertEquals(RunStatus.SAFE_STOPPED, run.status());
		assertTrue(run.lineage().stream().anyMatch(e -> "RETRY".equals(e.kind())), "expected RETRY");
		assertTrue(run.lineage().stream().anyMatch(e -> "FALLBACK_ACTIVATED".equals(e.kind())),
				"expected FALLBACK_ACTIVATED for impl_fallback");
		assertTrue(run.lineage().stream().anyMatch(e -> "FALLBACK_FAILED".equals(e.kind())
				|| "STAGE_FAILURE".equals(e.kind())));
		assertTrue(run.lineage().stream().anyMatch(e -> "ROLLBACK".equals(e.kind())), "expected ROLLBACK");
		assertTrue(run.rollbacks() >= 1);
		assertTrue(run.retries() >= 1);
		assertTrue(shortener.resolve("priorurl").isPresent(), "rollback must restore prior URL");

		boolean sawThreeDraftExhaustion = run.lineage().stream()
				.filter(e -> "STAGE_FAILURE".equals(e.kind()) || "RETRY_FAILED".equals(e.kind())
						|| "FALLBACK_FAILED".equals(e.kind()))
				.map(AuditEvent::message)
				.anyMatch(m -> m != null && m.contains("Model failed to produce"));
		assertTrue(sawThreeDraftExhaustion, "expected three-draft exhaustion message in lineage");
	}
}
