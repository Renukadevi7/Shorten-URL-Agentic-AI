package com.agentic.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

@Component
public class StageExecutor {

	private final UrlShortenerService shortener;

	public StageExecutor(UrlShortenerService shortener) {
		this.shortener = shortener;
	}

	public void execute(OrchestrationRun run, StageNode stage) {
		switch (stage.type()) {
			case REQUIREMENTS -> requirements(run, stage);
			case CLARIFICATION -> clarification(run, stage);
			case ARCHITECTURE -> architecture(run, stage);
			case IMPLEMENTATION -> implementation(run, stage);
			case TESTING -> testing(run, stage);
			case DOCUMENTATION -> documentation(run, stage);
			case RELEASE_READINESS -> release(run, stage);
		}
	}

	private void requirements(OrchestrationRun run, StageNode stage) {
		String req = run.requirement().toLowerCase();
		String normalized;
		if (run.scenario().equals("ambiguous") || req.contains("better") || req.contains("improve somehow")) {
			normalized = "Ambiguous intent detected: need measurable UX/API outcomes for URL shortener demo.";
			run.context().put("needsClarification", true);
		}
		else if (run.scenario().equals("brownfield") || req.contains("analytics")) {
			normalized = "Enhance existing URL shortener with click analytics and reliability controls.";
			run.context().put("needsClarification", false);
		}
		else {
			normalized = "Build greenfield URL shortener with create + redirect APIs and basic validation.";
			run.context().put("needsClarification", false);
		}
		run.setNormalizedProblem(normalized);
		stage.setOutput(normalized);
		stage.artifacts().put("problemStatement", normalized);
		run.audit(stage.id(), "REQUIREMENT_NORMALIZED", normalized, Map.of("scenario", run.scenario()));
	}

	private void clarification(OrchestrationRun run, StageNode stage) {
		String clarified = "Interpreted ambiguous ask as: ship create/redirect APIs, show one analytics metric, "
				+ "and produce a release checklist suitable for a leadership demo.";
		run.setNormalizedProblem(clarified);
		run.context().put("needsClarification", false);
		run.context().put("clarified", true);
		stage.setOutput(clarified);
		stage.artifacts().put("clarifiedRequirement", clarified);
		run.audit(stage.id(), "REPLAN", "Inserted clarification and continuing with concrete scope", Map.of());
	}

	private void architecture(OrchestrationRun run, StageNode stage) {
		Map<String, Object> design = new LinkedHashMap<>();
		design.put("components", new String[] { "UrlShortenerService", "OrchestratorService", "PolicyGuardrail" });
		design.put("apis", new String[] { "POST /api/urls", "GET /{code}", "GET /api/urls/{code}/stats" });
		design.put("storage", "In-memory ConcurrentHashMap (demo)");
		design.put("decision", "Keep product and control-plane co-located for orchestration visibility");
		stage.artifacts().put("architecture", design);
		stage.setOutput("Architecture drafted for URL shortener + orchestration control plane.");
		run.context().put("architectureReady", true);
		run.audit(stage.id(), "DESIGN", stage.output(), design);
	}

	private void implementation(OrchestrationRun run, StageNode stage) {
		if ("greenfield".equals(run.scenario()) || Boolean.TRUE.equals(run.context().get("clarified"))) {
			shortener.resetProduct();
			ShortUrl sample = shortener.shorten("https://example.com/agentic-demo");
			run.context().put("sampleCode", sample.code());
			run.context().put("sampleUrl", sample.longUrl());
			stage.artifacts().put("implemented", "Create + redirect APIs active");
			stage.artifacts().put("sampleCode", sample.code());
		}
		if ("brownfield".equals(run.scenario()) || run.requirement().toLowerCase().contains("analytics")) {
			if (shortener.featureFlags().get("urlCount").equals(0)) {
				ShortUrl sample = shortener.shorten("https://example.com/brownfield");
				run.context().put("sampleCode", sample.code());
				run.context().put("sampleUrl", sample.longUrl());
			}
			shortener.enableAnalytics();
			shortener.enableReliability();
			stage.artifacts().put("brownfield", "Analytics + reliability flags enabled");
		}
		stage.setOutput("Implementation artifacts applied to in-process shortener.");
		run.context().put("implementationDone", true);
		run.audit(stage.id(), "IMPLEMENT", stage.output(), new LinkedHashMap<>(stage.artifacts()));
	}

	private void testing(OrchestrationRun run, StageNode stage) {
		boolean passed = true;
		StringBuilder report = new StringBuilder();
		try {
			ShortUrl created = shortener.shorten("https://example.com/test");
			report.append("create=OK (").append(created.code()).append("); ");
			var resolved = shortener.resolve(created.code());
			if (resolved.isEmpty()) {
				passed = false;
				report.append("resolve=FAIL; ");
			}
			else {
				report.append("resolve=OK; ");
			}
			if (shortener.isAnalyticsEnabled()) {
				shortener.stats(created.code());
				report.append("stats=OK; ");
			}
			else {
				report.append("stats=SKIPPED; ");
			}
		}
		catch (Exception ex) {
			passed = false;
			report.append("error=").append(ex.getMessage());
		}
		run.context().put("testsPassed", passed);
		stage.artifacts().put("testReport", report.toString());
		stage.setOutput(passed ? "Tests passed" : "Tests failed: " + report);
		if (!passed) {
			throw new IllegalStateException("Testing stage failed validation");
		}
		run.audit(stage.id(), "TEST", stage.output(), Map.of("passed", passed));
	}

	private void documentation(OrchestrationRun run, StageNode stage) {
		String doc = """
				# URL Shortener API
				- POST /api/urls {"url":"https://..."}
				- GET /{code} redirect
				- GET /api/urls/{code}/stats (when analytics enabled)
				""".stripIndent();
		stage.artifacts().put("documentation", doc);
		stage.setOutput("API documentation artifact generated.");
		run.audit(stage.id(), "DOCS", stage.output(), Map.of());
	}

	private void release(OrchestrationRun run, StageNode stage) {
		Map<String, Object> checklist = new LinkedHashMap<>();
		checklist.put("testsPassed", run.context().get("testsPassed"));
		checklist.put("analyticsEnabled", shortener.isAnalyticsEnabled());
		checklist.put("reliabilityEnabled", shortener.isReliabilityEnabled());
		checklist.put("sampleCode", run.context().get("sampleCode"));
		checklist.put("operatorApproval", "required-and-recorded");
		stage.artifacts().put("releaseChecklist", checklist);
		stage.setOutput("Release readiness checklist prepared.");
		run.audit(stage.id(), "RELEASE_READY", stage.output(), checklist);
	}
}
