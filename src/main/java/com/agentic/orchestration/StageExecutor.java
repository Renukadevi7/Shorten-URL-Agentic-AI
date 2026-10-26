package com.agentic.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.shortener.ProductSnapshot;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

@Component
public class StageExecutor {

	private final UrlShortenerService shortener;
	private final PolicyGuardrail policyGuardrail;

	public StageExecutor(UrlShortenerService shortener, PolicyGuardrail policyGuardrail) {
		this.shortener = shortener;
		this.policyGuardrail = policyGuardrail;
	}

	public void execute(OrchestrationRun run, StageNode stage) {
		maybeInjectFault(run, stage);
		switch (stage.type()) {
			case REQUIREMENTS -> requirements(run, stage);
			case CLARIFICATION, REPLAN -> replan(run, stage);
			case ARCHITECTURE -> architecture(run, stage);
			case IMPLEMENTATION -> implementation(run, stage);
			case TESTING -> testing(run, stage, false);
			case TESTING_FALLBACK -> testing(run, stage, true);
			case DOCUMENTATION -> documentation(run, stage);
			case RELEASE_READINESS -> release(run, stage);
		}
		carryDecision(run, stage);
	}

	private void maybeInjectFault(OrchestrationRun run, StageNode stage) {
		Object fault = run.context().get("faultInjection");
		if (!(fault instanceof String faultType) || faultType.isBlank()) {
			return;
		}
		boolean once = !Boolean.FALSE.equals(run.context().get("faultOnce"));
		if ("FAIL_TESTING".equals(faultType) && stage.type() == StageType.TESTING) {
			if (once) {
				run.context().remove("faultInjection");
			}
			throw new IllegalStateException("Fault injection: FAIL_TESTING");
		}
		if ("FAIL_IMPLEMENTATION".equals(faultType) && stage.type() == StageType.IMPLEMENTATION) {
			if (once) {
				run.context().remove("faultInjection");
			}
			throw new IllegalStateException("Fault injection: FAIL_IMPLEMENTATION");
		}
	}

	private void requirements(OrchestrationRun run, StageNode stage) {
		String req = run.requirement().toLowerCase();
		String normalized;
		Map<String, Object> impact = new LinkedHashMap<>();
		if (run.scenario().equals("brownfield") || req.contains("analytics")) {
			normalized = "Enhance existing URL shortener with analytics, delete/expiry/alias, rate-limit and auth.";
			impact.put("modules", List.of("UrlShortenerService", "ShortUrlEntity", "UrlController", "ApiKeyAuthFilter"));
			impact.put("apis", List.of("POST /api/urls", "DELETE /api/urls/{code}", "GET /api/urls/{code}/stats"));
			impact.put("dataFlow", "API → auth/rate-limit → JPA/H2 → redirect/stats");
		}
		else if (run.scenario().equals("ambiguous") || req.contains("better") || req.contains("improve somehow")) {
			normalized = "Ambiguous intent: provisional scope = create/redirect + one analytics metric for demo.";
			impact.put("ambiguity", true);
			run.context().put("ambiguousDetected", true);
		}
		else {
			normalized = "Build greenfield URL shortener with create/redirect, expiry, alias, auth, rate-limit, H2.";
			impact.put("modules", List.of("UrlShortenerService", "UrlController", "ApiKeyAuthFilter", "RateLimitFilter"));
		}
		run.setNormalizedProblem(normalized);
		run.context().put("requirementVersion", run.context().getOrDefault("requirementVersion", 1));
		stage.setOutput(normalized);
		stage.artifacts().put("problemStatement", normalized);
		stage.artifacts().put("impactAnalysis", impact);
		run.audit(stage.id(), "REQUIREMENT_NORMALIZED", normalized, impact);
	}

	private void replan(OrchestrationRun run, StageNode stage) {
		String reason = String.valueOf(run.context().getOrDefault("replanReason", "Upstream output changed"));
		String clarified = "Re-planned scope after upstream change: keep create/redirect/stats, enforce HTTPS sample, "
				+ "retain auth + rate-limit, refresh architecture decisions.";
		run.setNormalizedProblem(clarified);
		run.context().put("requirementVersion",
				((Number) run.context().getOrDefault("requirementVersion", 1)).intValue() + 1);
		run.context().put("architectureReady", false);
		run.context().put("replanApplied", true);
		stage.artifacts().put("replanReason", reason);
		stage.artifacts().put("clarifiedRequirement", clarified);
		stage.setOutput(clarified);
		run.audit(stage.id(), "REPLAN", reason, Map.of("newRequirementVersion", run.context().get("requirementVersion")));
	}

	private void architecture(OrchestrationRun run, StageNode stage) {
		Map<String, Object> design = new LinkedHashMap<>();
		design.put("components", List.of("OrchestratorService", "StageGraph", "GatePolicy", "AuditLog",
				"UrlShortenerService", "ApiKeyAuthFilter", "RateLimitFilter"));
		design.put("apis", List.of("POST /api/urls", "GET /{code}", "DELETE /api/urls/{code}",
				"GET /api/urls/{code}/stats"));
		design.put("storage", "H2 file persistence via Spring Data JPA");
		design.put("orchestration", "DAG with concurrent ready-set + sync barrier, validating gates, fallback edges");
		design.put("impactedDataFlows", Map.of(
				"create", "client → API key → rate-limit → JPA",
				"redirect", "client → public GET → expiry/delete checks → optional click count",
				"orchestration", "UI/API → OrchestratorService → stage agents → product mutations"));
		design.put("version", run.context().getOrDefault("requirementVersion", 1));
		stage.artifacts().put("architecture", design);
		stage.setOutput("Architecture drafted with brownfield impact and orchestration control plane.");
		run.context().put("architectureReady", true);
		run.context().put("architectureVersion", design.get("version"));
		run.audit(stage.id(), "DESIGN", stage.output(), design);
	}

	private void implementation(OrchestrationRun run, StageNode stage) {
		ProductSnapshot before = shortener.snapshot();
		run.context().put("productSnapshotBeforeImpl", before.asMap());

		if ("greenfield".equals(run.scenario()) || "ambiguous".equals(run.scenario())
				|| Boolean.TRUE.equals(run.context().get("replanApplied"))) {
			shortener.resetProduct();
			ShortUrl sample = shortener.shorten("https://example.com/agentic-demo", "demoalias", 86400L);
			run.context().put("sampleCode", sample.code());
			run.context().put("sampleUrl", sample.longUrl());
			stage.artifacts().put("implemented", "Create/redirect/expiry/alias APIs active on H2");
			stage.artifacts().put("sampleCode", sample.code());
		}
		if ("brownfield".equals(run.scenario()) || run.requirement().toLowerCase().contains("analytics")) {
			if (shortener.featureFlags().get("urlCount").equals(0L)
					|| ((Number) shortener.featureFlags().get("urlCount")).longValue() == 0L) {
				ShortUrl sample = shortener.shorten("https://example.com/brownfield", null, 3600L);
				run.context().put("sampleCode", sample.code());
				run.context().put("sampleUrl", sample.longUrl());
			}
			shortener.enableAnalytics();
			shortener.enableReliability();
			stage.artifacts().put("brownfield", "Analytics + reliability enabled on persistent store");
			stage.artifacts().put("impactedModules",
					List.of("UrlShortenerService", "ShortUrlEntity", "UrlController"));
		}
		List<String> urlPolicy = policyGuardrail.validateUrlMutation(
				String.valueOf(run.context().get("sampleUrl")),
				String.valueOf(run.context().get("sampleCode")));
		if (!urlPolicy.isEmpty()) {
			throw new IllegalStateException("Policy blocked implementation outputs: " + String.join("; ", urlPolicy));
		}
		stage.setOutput("Implementation artifacts applied with pre-change snapshot captured.");
		run.context().put("implementationDone", true);
		run.audit(stage.id(), "IMPLEMENT", stage.output(), new LinkedHashMap<>(stage.artifacts()));
	}

	private void testing(OrchestrationRun run, StageNode stage, boolean fallback) {
		boolean passed = true;
		StringBuilder report = new StringBuilder(fallback ? "fallback:" : "primary:");
		try {
			ShortUrl created = shortener.shorten("https://example.com/test-" + stage.id(), null, 600L);
			report.append("create=OK(").append(created.code()).append(");");
			var resolved = shortener.resolve(created.code());
			if (resolved.isEmpty()) {
				passed = false;
				report.append("resolve=FAIL;");
			}
			else {
				report.append("resolve=OK;");
			}
			if (!fallback) {
				boolean deleted = shortener.delete(created.code());
				report.append("delete=").append(deleted ? "OK;" : "FAIL;");
				if (!deleted) {
					passed = false;
				}
				if (shortener.isAnalyticsEnabled()) {
					ShortUrl statsTarget = shortener.shorten("https://example.com/stats", null, 600L);
					shortener.resolve(statsTarget.code());
					shortener.stats(statsTarget.code());
					report.append("stats=OK;");
				}
			}
		}
		catch (Exception ex) {
			passed = false;
			report.append("error=").append(ex.getMessage());
		}
		run.context().put("testsPassed", passed);
		stage.artifacts().put("testReport", report.toString());
		stage.artifacts().put("passed", passed);
		stage.artifacts().put("testPassed", passed);
		stage.setOutput(passed ? (fallback ? "Fallback tests passed" : "Tests passed")
				: "Tests failed: " + report);
		if (!passed) {
			throw new IllegalStateException("Testing stage failed validation: " + report);
		}
		run.audit(stage.id(), "TEST", stage.output(), Map.of("passed", passed, "fallback", fallback));
	}

	private void documentation(OrchestrationRun run, StageNode stage) {
		// simulate parallel work duration so concurrent execution is observable
		sleep(120);
		String doc = """
				# URL Shortener API
				- POST /api/urls {"url","customAlias","ttlSeconds"} (requires X-API-Key)
				- GET /{code} public redirect (respects expiry/delete)
				- DELETE /api/urls/{code} (requires X-API-Key)
				- GET /api/urls/{code}/stats (requires X-API-Key)
				- Rate limit: agentic.rate-limit.requests-per-minute
				""".stripIndent();
		stage.artifacts().put("documentation", doc);
		run.context().put("docsReady", true);
		stage.setOutput("API documentation artifact generated.");
		run.audit(stage.id(), "DOCS", stage.output(), Map.of());
	}

	private void release(OrchestrationRun run, StageNode stage) {
		Map<String, Object> checklist = new LinkedHashMap<>();
		checklist.put("testsPassed", run.context().get("testsPassed"));
		checklist.put("docsReady", run.context().get("docsReady"));
		checklist.put("analyticsEnabled", shortener.isAnalyticsEnabled());
		checklist.put("reliabilityEnabled", shortener.isReliabilityEnabled());
		checklist.put("sampleCode", run.context().get("sampleCode"));
		checklist.put("persistentStorage", "H2");
		checklist.put("auth", "X-API-Key");
		stage.artifacts().put("releaseChecklist", checklist);
		stage.setOutput("Release readiness checklist prepared.");
		run.audit(stage.id(), "RELEASE_READY", stage.output(), checklist);
	}

	private void carryDecision(OrchestrationRun run, StageNode stage) {
		@SuppressWarnings("unchecked")
		Map<String, Object> decisions = (Map<String, Object>) run.context()
				.computeIfAbsent("decisions", k -> new LinkedHashMap<String, Object>());
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("stageId", stage.id());
		entry.put("type", stage.type().name());
		entry.put("output", stage.output());
		entry.put("artifacts", new LinkedHashMap<>(stage.artifacts()));
		decisions.put(stage.id(), entry);
		run.context().put("lastStageId", stage.id());
		run.context().put("lastStageType", stage.type().name());
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
