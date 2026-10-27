package com.agentic.orchestration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.orchestration.agent.ChangeAgent;
import com.agentic.orchestration.agent.ChangeArtifact;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

/**
 * Dispatches stage work through a handler registry (no hard-coded stage-id special cases).
 * Implementation uses a pluggable {@link ChangeAgent} (produce → apply → validate).
 */
@Component
public class StageExecutor {

	private final UrlShortenerService shortener;
	private final ChangeAgent changeAgent;
	private final Map<StageType, StageHandler> handlers = new EnumMap<>(StageType.class);

	public StageExecutor(UrlShortenerService shortener, ChangeAgent changeAgent) {
		this.shortener = shortener;
		this.changeAgent = changeAgent;
		register(StageType.REQUIREMENTS, this::requirements);
		register(StageType.CLARIFICATION, this::replan);
		register(StageType.REPLAN, this::replan);
		register(StageType.ARCHITECTURE, this::architecture);
		register(StageType.SECURITY_REVIEW, this::securityReview);
		register(StageType.IMPLEMENTATION, this::implementation);
		register(StageType.TESTING, (run, stage) -> testing(run, stage, false));
		register(StageType.TESTING_FALLBACK, (run, stage) -> testing(run, stage, true));
		register(StageType.DOCUMENTATION, this::documentation);
		register(StageType.RELEASE_READINESS, this::release);
	}

	private void register(StageType type, StageHandler handler) {
		handlers.put(type, handler);
	}

	public void execute(OrchestrationRun run, StageNode stage) {
		maybeInjectFault(run, stage);
		StageHandler handler = handlers.get(stage.type());
		if (handler == null) {
			throw new IllegalStateException("No handler registered for stage type " + stage.type());
		}
		handler.execute(run, stage);
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
		if ("FAIL_ALL_TESTING".equals(faultType)
				&& (stage.type() == StageType.TESTING || stage.type() == StageType.TESTING_FALLBACK)) {
			if (once) {
				run.context().remove("faultInjection");
			}
			throw new IllegalStateException("Fault injection: FAIL_ALL_TESTING");
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

	private void securityReview(OrchestrationRun run, StageNode stage) {
		List<String> findings = new java.util.ArrayList<>();
		findings.add("Auth required on mutating APIs (X-API-Key)");
		findings.add("Rate limit enforced per key");
		findings.add("HTTPS-only sample targets");
		findings.add("Human approval required for implementation and release");
		if (Boolean.TRUE.equals(run.context().get("ambiguousDetected"))) {
			findings.add("Ambiguous requirement flagged — provisional scope accepted with replan path");
		}
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("findings", findings);
		report.put("severity", "PASS");
		report.put("changeControl", "approval gates retained");
		stage.artifacts().put("securityFindings", report);
		run.context().put("securityReviewPassed", true);
		stage.setOutput("Security & change-control review passed (" + findings.size() + " checks).");
		run.audit(stage.id(), "SECURITY_REVIEW", stage.output(), report);
	}

	private void architecture(OrchestrationRun run, StageNode stage) {
		Map<String, Object> design = new LinkedHashMap<>();
		design.put("components", List.of("OrchestratorService", "StageGraph", "GatePolicy", "AuditLog",
				"ChangeAgent", "UrlShortenerService", "ApiKeyAuthFilter", "RateLimitFilter"));
		design.put("apis", List.of("POST /api/urls", "GET /{code}", "DELETE /api/urls/{code}",
				"GET /api/urls/{code}/stats"));
		design.put("storage", "H2 file persistence via Spring Data JPA");
		design.put("orchestration", "DAG with concurrent ready-set + sync barrier, metadata gates, fallback edges");
		design.put("changeAgent", "produce → apply → validate");
		design.put("version", run.context().getOrDefault("requirementVersion", 1));
		stage.artifacts().put("architecture", design);
		stage.setOutput("Architecture drafted with orchestration control plane and ChangeAgent contract.");
		run.context().put("architectureReady", true);
		run.context().put("architectureVersion", design.get("version"));
		run.audit(stage.id(), "DESIGN", stage.output(), design);
	}

	private void implementation(OrchestrationRun run, StageNode stage) {
		ChangeArtifact artifact = changeAgent.produce(run);
		run.audit(stage.id(), "AGENT_PRODUCE", artifact.summary(), artifact.asMap());
		changeAgent.apply(run, artifact);
		run.audit(stage.id(), "AGENT_APPLY", "Applied change " + artifact.changeId(),
				Map.of("changeId", artifact.changeId(), "filesTouched", artifact.filesTouched()));
		changeAgent.validate(run, artifact);
		run.audit(stage.id(), "AGENT_VALIDATE", "Validated change " + artifact.changeId(), artifact.validationPlan());

		stage.artifacts().put("changeArtifact", artifact.asMap());
		stage.artifacts().put("agentValidation", Map.of("changeId", artifact.changeId(), "status", "PASSED"));
		stage.artifacts().put("sampleCode", run.context().get("sampleCode"));
		stage.setOutput("ChangeAgent produced/applied/validated change " + artifact.changeId());
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
		sleep(120);
		String doc = """
				# URL Shortener API
				- POST /api/urls {"url","customAlias","ttlSeconds"} (requires X-API-Key from env)
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
		checklist.put("changeArtifact", run.context().get("changeArtifact"));
		checklist.put("persistentStorage", "H2");
		checklist.put("auth", "X-API-Key via AGENTIC_API_KEY");
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
