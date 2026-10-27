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
 * Deterministic agent that still follows produce → apply → validate.
 * Demonstrates how an LLM-backed agent would plug in without requiring an API key.
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

		Map<String, Object> operations = new LinkedHashMap<>();
		operations.put("captureSnapshot", true);
		operations.put("resetProduct", reset);
		operations.put("enableAnalytics", brownfield || reset);
		operations.put("enableReliability", brownfield || reset);
		operations.put("sampleUrl", brownfield ? "https://example.com/brownfield" : "https://example.com/agentic-demo");
		operations.put("sampleAlias", brownfield ? null : "demoalias");
		operations.put("ttlSeconds", brownfield ? 3600L : 86400L);

		Map<String, Object> validation = new LinkedHashMap<>();
		validation.put("requireHttpsSample", true);
		validation.put("requireResolvableSample", true);
		validation.put("requirePolicyClean", true);

		String summary = brownfield
				? "Enable analytics/reliability on existing shortener and seed a sample link"
				: "Provision greenfield shortener capabilities with sample HTTPS alias";

		return new ChangeArtifact(
				"chg-" + UUID.randomUUID().toString().substring(0, 8),
				summary,
				"Derived from normalized problem + architecture context for scenario=" + run.scenario(),
				List.of("UrlShortenerService", "ShortUrlEntity", "UrlController"),
				operations,
				validation);
	}

	@Override
	public void apply(OrchestrationRun run, ChangeArtifact artifact) {
		ProductSnapshot before = shortener.snapshot();
		run.context().put("productSnapshotBeforeImpl", before.asMap());
		run.context().put("changeArtifact", artifact.asMap());

		Map<String, Object> ops = artifact.operations();
		if (Boolean.TRUE.equals(ops.get("resetProduct"))) {
			shortener.resetProduct();
		}
		String sampleUrl = String.valueOf(ops.get("sampleUrl"));
		String alias = ops.get("sampleAlias") == null ? null : String.valueOf(ops.get("sampleAlias"));
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
	}

	@Override
	public void validate(OrchestrationRun run, ChangeArtifact artifact) {
		Map<String, Object> plan = artifact.validationPlan();
		String sampleUrl = String.valueOf(run.context().get("sampleUrl"));
		String sampleCode = String.valueOf(run.context().get("sampleCode"));

		if (Boolean.TRUE.equals(plan.get("requireHttpsSample")) && !sampleUrl.startsWith("https://")) {
			throw new IllegalStateException("Change validation failed: sample URL must be HTTPS");
		}
		if (Boolean.TRUE.equals(plan.get("requireResolvableSample"))
				&& shortener.resolve(sampleCode).isEmpty()) {
			throw new IllegalStateException("Change validation failed: sample code is not resolvable");
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
	}
}
