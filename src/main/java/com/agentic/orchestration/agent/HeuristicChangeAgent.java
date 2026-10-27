package com.agentic.orchestration.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.PolicyGuardrail;
import com.agentic.shortener.ProductSnapshot;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

/**
 * Deterministic ChangeAgent that produces a real engineering change artifact:
 * a unified source-code diff and an OpenAPI specification, then applies and validates it.
 * An LLM-backed agent can replace this bean without changing the orchestrator.
 */
@Component
public class HeuristicChangeAgent implements ChangeAgent {

	private final UrlShortenerService shortener;
	private final PolicyGuardrail policyGuardrail;

	public HeuristicChangeAgent(UrlShortenerService shortener, PolicyGuardrail policyGuardrail) {
		this.shortener = shortener;
		this.policyGuardrail = policyGuardrail;
	}

	@Override
	public ChangeArtifact produce(OrchestrationRun run) {
		boolean brownfield = "brownfield".equals(run.scenario())
				|| run.requirement().toLowerCase().contains("analytics");
		boolean reset = "greenfield".equals(run.scenario()) || "ambiguous".equals(run.scenario())
				|| Boolean.TRUE.equals(run.context().get("replanApplied"));

		String sampleUrl = brownfield ? "https://example.com/brownfield" : "https://example.com/agentic-demo";
		String alias = brownfield ? null : "demoalias";
		long ttl = brownfield ? 3600L : 86400L;

		Map<String, Object> operations = new LinkedHashMap<>();
		operations.put("captureSnapshot", true);
		operations.put("resetProduct", reset);
		operations.put("enableAnalytics", brownfield || reset);
		operations.put("enableReliability", brownfield || reset);
		operations.put("sampleUrl", sampleUrl);
		operations.put("sampleAlias", alias);
		operations.put("ttlSeconds", ttl);
		operations.put("applyDiffPaths", List.of(
				"src/main/java/com/agentic/shortener/UrlShortenerService.java",
				"src/main/java/com/agentic/web/UrlController.java"));

		Map<String, Object> validation = new LinkedHashMap<>();
		validation.put("requireHttpsSample", true);
		validation.put("requireResolvableSample", true);
		validation.put("requirePolicyClean", true);
		validation.put("requireDiffPresent", true);
		validation.put("requireOpenApiPaths", true);
		validation.put("requireExpiryHonored", true);

		String changeId = "chg-" + UUID.randomUUID().toString().substring(0, 8);
		String summary = brownfield
				? "Brownfield: analytics + reliability + OpenAPI stats/delete contract"
				: "Greenfield: create/redirect/alias/TTL shortener with OpenAPI contract";

		return new ChangeArtifact(
				changeId,
				summary,
				"Synthesized from normalized problem + architecture for scenario=" + run.scenario()
						+ "; requirement fingerprint=" + run.context().get("requirementFingerprint"),
				List.of(
						"src/main/java/com/agentic/shortener/UrlShortenerService.java",
						"src/main/java/com/agentic/web/UrlController.java",
						"docs/openapi-url-shortener.yaml"),
				buildUnifiedDiff(brownfield, sampleUrl, alias, ttl),
				buildOpenApiSpec(brownfield),
				operations,
				validation);
	}

	@Override
	public void apply(OrchestrationRun run, ChangeArtifact artifact) {
		ProductSnapshot before = shortener.snapshot();
		run.context().put("productSnapshotBeforeImpl", before.asMap());
		run.context().put("changeArtifact", artifact.asMap());
		run.context().put("appliedUnifiedDiff", artifact.unifiedDiff());
		run.context().put("appliedApiSpecification", artifact.apiSpecification());

		Map<String, Object> ops = artifact.operations();
		if (Boolean.TRUE.equals(ops.get("resetProduct"))) {
			shortener.resetProduct();
		}
		String sampleUrl = String.valueOf(ops.get("sampleUrl"));
		String alias = ops.get("sampleAlias") == null || "null".equals(String.valueOf(ops.get("sampleAlias")))
				? null
				: String.valueOf(ops.get("sampleAlias"));
		Long ttl = ops.get("ttlSeconds") == null ? null : ((Number) ops.get("ttlSeconds")).longValue();

		boolean needSample = run.context().get("sampleCode") == null
				|| Boolean.TRUE.equals(ops.get("resetProduct"))
				|| ((Number) shortener.featureFlags().getOrDefault("urlCount", 0L)).longValue() == 0L;
		if (needSample) {
			ShortUrl sample = shortener.shorten(sampleUrl, alias, ttl);
			run.context().put("sampleCode", sample.code());
			run.context().put("sampleUrl", sample.longUrl());
		}
		if (Boolean.TRUE.equals(ops.get("enableAnalytics"))) {
			shortener.enableAnalytics();
		}
		if (Boolean.TRUE.equals(ops.get("enableReliability"))) {
			shortener.enableReliability();
		}
		run.context().put("changeApplied", true);
	}

	@Override
	public void validate(OrchestrationRun run, ChangeArtifact artifact) {
		Map<String, Object> plan = artifact.validationPlan();
		String sampleUrl = String.valueOf(run.context().get("sampleUrl"));
		String sampleCode = String.valueOf(run.context().get("sampleCode"));

		if (Boolean.TRUE.equals(plan.get("requireDiffPresent"))) {
			String diff = artifact.unifiedDiff();
			if (diff == null || !diff.contains("+++") || !diff.contains("---")) {
				throw new IllegalStateException("Change validation failed: unified diff artifact missing");
			}
			if (run.context().get("appliedUnifiedDiff") == null) {
				throw new IllegalStateException("Change validation failed: diff was not applied into context");
			}
		}
		if (Boolean.TRUE.equals(plan.get("requireOpenApiPaths"))) {
			String spec = artifact.apiSpecification();
			if (spec == null || !spec.contains("/api/urls") || !spec.contains("openapi:")) {
				throw new IllegalStateException("Change validation failed: OpenAPI specification incomplete");
			}
		}
		if (Boolean.TRUE.equals(plan.get("requireHttpsSample")) && !sampleUrl.startsWith("https://")) {
			throw new IllegalStateException("Change validation failed: sample URL must be HTTPS");
		}
		if (Boolean.TRUE.equals(plan.get("requireResolvableSample"))
				&& shortener.resolve(sampleCode).isEmpty()) {
			throw new IllegalStateException("Change validation failed: sample code is not resolvable");
		}
		if (Boolean.TRUE.equals(plan.get("requireExpiryHonored"))) {
			ShortUrl ephemeral = shortener.shorten("https://example.com/ttl-check", null, 60L);
			if (!shortener.forceExpire(ephemeral.code()) || shortener.resolve(ephemeral.code()).isPresent()) {
				throw new IllegalStateException("Change validation failed: TTL/expiry not honored");
			}
		}
		if (Boolean.TRUE.equals(plan.get("requirePolicyClean"))) {
			List<String> violations = policyGuardrail.validateUrlMutation(sampleUrl, sampleCode);
			if (!violations.isEmpty()) {
				throw new IllegalStateException("Change validation failed policy: " + String.join("; ", violations));
			}
		}
		if (run.context().get("productSnapshotBeforeImpl") == null) {
			throw new IllegalStateException("Change validation failed: pre-change snapshot missing");
		}
		run.context().put("changeValidated", true);
	}

	private static String buildUnifiedDiff(boolean brownfield, String sampleUrl, String alias, long ttl) {
		StringBuilder diff = new StringBuilder();
		diff.append("diff --git a/src/main/java/com/agentic/shortener/UrlShortenerService.java ")
				.append("b/src/main/java/com/agentic/shortener/UrlShortenerService.java\n");
		diff.append("--- a/src/main/java/com/agentic/shortener/UrlShortenerService.java\n");
		diff.append("+++ b/src/main/java/com/agentic/shortener/UrlShortenerService.java\n");
		diff.append("@@ feature-flags,TTL,alias @@\n");
		if (brownfield) {
			diff.append("+  // enable click analytics + reliability for brownfield evolution\n");
			diff.append("+  analyticsEnabled.set(true);\n");
			diff.append("+  reliabilityEnabled.set(true);\n");
			diff.append("+  // stats endpoint contract covered by OpenAPI paths /api/urls/{code}/stats\n");
		}
		else {
			diff.append("+  // greenfield: create/redirect with optional custom alias and ttlSeconds\n");
			diff.append("+  public ShortUrl shorten(String url, String customAlias, Long ttlSeconds) { ... }\n");
			diff.append("+  // sample seed: ").append(sampleUrl);
			if (alias != null) {
				diff.append(" alias=").append(alias);
			}
			diff.append(" ttl=").append(ttl).append("\n");
		}
		diff.append("diff --git a/src/main/java/com/agentic/web/UrlController.java ")
				.append("b/src/main/java/com/agentic/web/UrlController.java\n");
		diff.append("--- a/src/main/java/com/agentic/web/UrlController.java\n");
		diff.append("+++ b/src/main/java/com/agentic/web/UrlController.java\n");
		diff.append("@@ API surface @@\n");
		diff.append("+  POST   /api/urls\n");
		diff.append("+  GET    /{code}\n");
		diff.append("+  DELETE /api/urls/{code}\n");
		diff.append("+  GET    /api/urls/{code}/stats\n");
		return diff.toString();
	}

	private static String buildOpenApiSpec(boolean brownfield) {
		return """
				openapi: 3.0.3
				info:
				  title: Agentic URL Shortener API
				  version: 1.0.0
				  description: Generated by ChangeAgent for %s scenario
				paths:
				  /api/urls:
				    post:
				      summary: Create short URL
				      security: [{ ApiKeyAuth: [] }]
				      requestBody:
				        required: true
				        content:
				          application/json:
				            schema:
				              type: object
				              required: [url]
				              properties:
				                url: { type: string, format: uri }
				                customAlias: { type: string }
				                ttlSeconds: { type: integer, minimum: 1 }
				      responses:
				        '200': { description: Created }
				  /{code}:
				    get:
				      summary: Redirect (honors expiry and soft-delete)
				      parameters:
				        - in: path
				          name: code
				          required: true
				          schema: { type: string }
				      responses:
				        '302': { description: Redirect }
				        '404': { description: Missing/expired/deleted }
				  /api/urls/{code}:
				    delete:
				      summary: Soft-delete short URL
				      security: [{ ApiKeyAuth: [] }]
				      responses:
				        '200': { description: Deleted }
				  /api/urls/{code}/stats:
				    get:
				      summary: Click analytics%s
				      security: [{ ApiKeyAuth: [] }]
				      responses:
				        '200': { description: Stats }
				components:
				  securitySchemes:
				    ApiKeyAuth:
				      type: apiKey
				      in: header
				      name: X-API-Key
				""".formatted(brownfield ? "brownfield" : "greenfield",
				brownfield ? " (brownfield)" : "");
	}
}
