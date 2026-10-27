package com.agentic.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.agentic.orchestration.agent.ChangeAgent;
import com.agentic.orchestration.agent.ChangeArtifact;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

/**
 * Dispatches by JSON {@code handler} name (not {@link StageType} and not stage id).
 * Gate flags come from each stage's {@code produces} metadata via {@link #applyProduces};
 * handlers must not hard-code keys such as {@code architectureReady} / {@code testsPassed}.
 * Config-only stages use {@code handler=markProduces} with no new Java.
 */
@Component
public class StageExecutor {

	private final UrlShortenerService shortener;
	private final ChangeAgent changeAgent;
	private final Map<String, StageHandler> handlers = new ConcurrentHashMap<>();

	public StageExecutor(UrlShortenerService shortener, ChangeAgent changeAgent) {
		this.shortener = shortener;
		this.changeAgent = changeAgent;
		register("requirements", this::requirements);
		register("clarification", this::replan);
		register("replan", this::replan);
		register("architecture", this::architecture);
		register("securityReview", this::securityReview);
		register("implementation", this::implementation);
		register("testing", (run, stage) -> testing(run, stage, false));
		register("testingFallback", (run, stage) -> testing(run, stage, true));
		register("documentation", this::documentation);
		register("releaseReadiness", this::release);
		// Generic: any JSON stage can use handler=markProduces without new Java.
		register("markProduces", this::markProduces);
	}

	private void register(String name, StageHandler handler) {
		handlers.put(name, handler);
	}

	/** Visible for tests: registered handler names. */
	public boolean hasHandler(String name) {
		return handlers.containsKey(name);
	}

	public void execute(OrchestrationRun run, StageNode stage) {
		maybeInjectFault(run, stage, false);
		StageHandler handler = handlers.get(stage.handler());
		if (handler == null) {
			throw new IllegalStateException("No handler registered for name '" + stage.handler()
					+ "' (stage id=" + stage.id() + ")");
		}
		handler.execute(run, stage);
		// Declarative produces: fill any keys the handler left unset (success path).
		fillProducesIfAbsent(run, stage);
		carryDecision(run, stage);
	}

	/** Success path: set each {@code produces} key to true unless already present. */
	static void fillProducesIfAbsent(OrchestrationRun run, StageNode stage) {
		for (String key : stage.producesContext()) {
			run.context().putIfAbsent(key, true);
		}
	}

	/** Failure / rollback: force every {@code produces} key to {@code value}. */
	static void forceProduces(OrchestrationRun run, StageNode stage, boolean value) {
		for (String key : stage.producesContext()) {
			run.context().put(key, value);
		}
	}

	/** Metadata-only stage: sets declared produces keys / a tiny artifact map. */
	private void markProduces(OrchestrationRun run, StageNode stage) {
		Map<String, Object> produced = new LinkedHashMap<>();
		for (String key : stage.producesContext()) {
			run.context().put(key, true);
			produced.put(key, true);
		}
		stage.artifacts().put("produced", produced);
		stage.setOutput("Marked produces: " + stage.producesContext());
		run.audit(stage.id(), "MARK_PRODUCES", stage.output(), produced);
	}

	/**
	 * Generic fault injection: matches {@link FaultSpec#stageId()} to the executing stage id.
	 * kind=FAIL fires before the handler; kind=FAIL_AFTER_SNAPSHOT fires after impl snapshot.
	 */
	private void maybeInjectFault(OrchestrationRun run, StageNode stage, boolean afterSnapshotOnly) {
		FaultSpec fault = FaultSpec.fromContext(run.context().get("faultInjection"));
		if (fault == null || fault.stageId() == null || fault.stageId().isBlank()) {
			return;
		}
		boolean matches = java.util.Arrays.stream(fault.stageId().split(","))
				.map(String::trim)
				.anyMatch(id -> id.equals(stage.id()));
		if (!matches) {
			return;
		}
		boolean afterSnapshotKind = "FAIL_AFTER_SNAPSHOT".equalsIgnoreCase(fault.kind());
		if (afterSnapshotKind != afterSnapshotOnly) {
			return;
		}
		if (!afterSnapshotKind && !"FAIL".equalsIgnoreCase(fault.kind())) {
			return;
		}
		if (fault.once()) {
			run.context().remove("faultInjection");
		}
		throw new IllegalStateException("Fault injection: kind=" + fault.kind() + " stageId=" + fault.stageId());
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
		run.context().put("normalizedProblem", normalized);
		run.context().put("requirementVersion", run.context().getOrDefault("requirementVersion", 1));
		stage.setOutput(normalized);
		stage.artifacts().put("problemStatement", normalized);
		stage.artifacts().put("impactAnalysis", impact);
		run.audit(stage.id(), "REQUIREMENT_NORMALIZED", normalized, impact);
	}

	private void replan(OrchestrationRun run, StageNode stage) {
		String reason = String.valueOf(run.context().getOrDefault("replanReason", "Upstream output changed"));
		String upstream = String.valueOf(run.context().getOrDefault("upstreamRequirement", run.requirement()));
		String lower = upstream.toLowerCase();
		StringBuilder clarified = new StringBuilder("Re-planned from upstream requirement: ");
		clarified.append(upstream.trim());
		clarified.append(" | derived scope:");
		if (lower.contains("https")) {
			clarified.append(" HTTPS-only samples;");
		}
		if (lower.contains("stat") || lower.contains("analytics")) {
			clarified.append(" click analytics/stats;");
		}
		if (lower.contains("delete") || lower.contains("expir") || lower.contains("ttl")) {
			clarified.append(" delete/expiry controls;");
		}
		if (lower.contains("alias")) {
			clarified.append(" custom aliases;");
		}
		clarified.append(" retain auth + rate-limit; refresh architecture.");
		String clarifiedText = clarified.toString();
		run.setNormalizedProblem(clarifiedText);
		run.context().put("requirementVersion",
				((Number) run.context().getOrDefault("requirementVersion", 1)).intValue() + 1);
		// Downstream gate flags are cleared by OrchestratorService.dynamicReplan via produces metadata —
		// this handler must not hard-code architectureReady / testsPassed / docsReady.
		run.context().put("replanApplied", true);
		run.context().put("replanDerivedFrom", upstream);
		run.context().put("normalizedProblem", clarifiedText);
		stage.artifacts().put("replanReason", reason);
		stage.artifacts().put("clarifiedRequirement", clarifiedText);
		stage.artifacts().put("upstreamRequirement", upstream);
		stage.setOutput(clarifiedText);
		run.audit(stage.id(), "REPLAN", reason, Map.of(
				"newRequirementVersion", run.context().get("requirementVersion"),
				"upstreamRequirement", upstream));
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
		run.context().put("architectureVersion", design.get("version"));
		run.audit(stage.id(), "DESIGN", stage.output(), design);
	}

	private void implementation(OrchestrationRun run, StageNode stage) {
		ChangeArtifact artifact = changeAgent.produce(run);
		run.audit(stage.id(), "AGENT_PRODUCE", artifact.summary(), artifact.asMap());
		changeAgent.apply(run, artifact);
		run.audit(stage.id(), "AGENT_APPLY", "Applied change " + artifact.changeId(),
				Map.of("changeId", artifact.changeId(), "filesTouched", artifact.filesTouched()));
		// Snapshot is now captured — stage-id faults with FAIL_AFTER_SNAPSHOT exercise rollback.
		maybeInjectFault(run, stage, true);
		changeAgent.validate(run, artifact);
		run.audit(stage.id(), "AGENT_VALIDATE", "Validated change " + artifact.changeId(), artifact.validationPlan());

		stage.artifacts().put("changeArtifact", artifact.asMap());
		stage.artifacts().put("agentValidation", Map.of("changeId", artifact.changeId(), "status", "PASSED"));
		stage.artifacts().put("sampleCode", run.context().get("sampleCode"));
		stage.setOutput("ChangeAgent produced/applied/validated change " + artifact.changeId());
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
		stage.artifacts().put("testReport", report.toString());
		stage.artifacts().put("passed", passed);
		stage.artifacts().put("testPassed", passed);
		stage.setOutput(passed ? (fallback ? "Fallback tests passed" : "Tests passed")
				: "Tests failed: " + report);
		if (!passed) {
			forceProduces(run, stage, false);
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
		stage.setOutput("API documentation artifact generated.");
		run.audit(stage.id(), "DOCS", stage.output(), Map.of());
	}

	private void release(OrchestrationRun run, StageNode stage) {
		Map<String, Object> checklist = new LinkedHashMap<>();
		// Checklist keys come from this stage's declared consumes — not a hard-coded list.
		for (String key : stage.consumesContext()) {
			checklist.put(key, run.context().get(key));
		}
		for (String key : stage.requiredEntryContext()) {
			checklist.putIfAbsent(key, run.context().get(key));
		}
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
				.computeIfAbsent("decisions", k -> new ConcurrentHashMap<String, Object>());
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("stageId", stage.id());
		entry.put("handler", stage.handler());
		entry.put("type", stage.type().name());
		entry.put("output", stage.output());
		entry.put("artifacts", new LinkedHashMap<>(stage.artifacts()));
		entry.put("recordedAtMs", System.currentTimeMillis());
		decisions.put(stage.id(), entry);
		run.context().put("lastStageId", stage.id());
		run.context().put("lastStageHandler", stage.handler());
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
