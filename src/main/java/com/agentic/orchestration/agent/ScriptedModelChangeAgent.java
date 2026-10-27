package com.agentic.orchestration.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.PolicyGuardrail;
import com.agentic.shortener.ProductSnapshot;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

/**
 * Offline scripted "model" ChangeAgent: drafts real source into a workspace, compiles it,
 * runs checks, repairs on failure, then applies product mutations under snapshot.
 * Demonstrates generate → validate → repair without requiring a live LLM API key.
 */
@Primary
@Component
public class ScriptedModelChangeAgent implements ChangeAgent {

	private final UrlShortenerService shortener;
	private final PolicyGuardrail policyGuardrail;

	public ScriptedModelChangeAgent(UrlShortenerService shortener, PolicyGuardrail policyGuardrail) {
		this.shortener = shortener;
		this.policyGuardrail = policyGuardrail;
	}

	@Override
	public ChangeArtifact produce(OrchestrationRun run) {
		String changeId = "chg-" + UUID.randomUUID().toString().substring(0, 8);
		Path workspace = Path.of("data", "agent-workspace", run.id(), changeId);
		try {
			Files.createDirectories(workspace.resolve("src/main/java/com/agentic/generated"));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to create agent workspace", ex);
		}

		List<Map<String, Object>> drafts = new ArrayList<>();
		String finalSource = null;
		String relative = "src/main/java/com/agentic/generated/AliasPolicy.java";
		int acceptedDraft = -1;

		for (int draft = 1; draft <= 2; draft++) {
			boolean broken = draft == 1;
			String source = broken ? brokenAliasPolicy() : validAliasPolicy(run);
			Path file = workspace.resolve(relative);
			try {
				Files.writeString(file, source, StandardCharsets.UTF_8);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Unable to write draft", ex);
			}
			Map<String, Object> result = validateDraft(workspace, file, source);
			drafts.add(result);
			if (Boolean.TRUE.equals(result.get("accepted"))) {
				finalSource = source;
				acceptedDraft = draft;
				break;
			}
		}
		if (finalSource == null) {
			throw new IllegalStateException("Model failed to produce a compiling change after repair attempts");
		}

		String diff = """
				diff --git a/%s b/%s
				--- a/%s
				+++ b/%s
				@@
				%s
				""".formatted(relative, relative, relative, relative, finalSource);

		String openApi = """
				openapi: 3.0.3
				info:
				  title: Agentic URL Shortener API
				  version: 1.0.0
				paths:
				  /api/urls:
				    post:
				      summary: Create short URL with alias/TTL
				  /{code}:
				    get:
				      summary: Redirect
				  /api/urls/{code}:
				    delete:
				      summary: Soft-delete
				  /api/urls/{code}/stats:
				    get:
				      summary: Click analytics
				""";

		boolean brownfield = "brownfield".equals(run.scenario())
				|| run.requirement().toLowerCase().contains("analytics");
		boolean reset = !"brownfield".equals(run.scenario()) || Boolean.TRUE.equals(run.context().get("replanApplied"));

		Map<String, Object> operations = new LinkedHashMap<>();
		operations.put("workspace", workspace.toString());
		operations.put("acceptedDraft", acceptedDraft);
		operations.put("drafts", drafts);
		operations.put("resetProduct", reset);
		operations.put("enableAnalytics", true);
		operations.put("enableReliability", true);
		operations.put("sampleUrl", brownfield ? "https://example.com/brownfield" : "https://example.com/agentic-demo");
		operations.put("sampleAlias", brownfield ? null : "demoalias");
		operations.put("ttlSeconds", brownfield ? 3600L : 86400L);

		Map<String, Object> validation = new LinkedHashMap<>();
		validation.put("requireHttpsSample", true);
		validation.put("requireResolvableSample", true);
		validation.put("requirePolicyClean", true);
		validation.put("requireDiffPresent", true);
		validation.put("requireOpenApiPaths", true);
		validation.put("requireCompilePassed", true);
		validation.put("requireExpiryHonored", true);

		run.context().put("agentDrafts", drafts);
		run.context().put("agentWorkspace", workspace.toString());

		return new ChangeArtifact(
				changeId,
				"Scripted model change after " + acceptedDraft + " draft(s): AliasPolicy + product wiring",
				"Draft/compile/repair loop for scenario=" + run.scenario(),
				List.of(relative, "UrlShortenerService.java"),
				diff,
				openApi,
				operations,
				validation);
	}

	@Override
	public void apply(OrchestrationRun run, ChangeArtifact artifact) {
		if (run.context().get("productSnapshotBeforeImpl") == null) {
			ProductSnapshot before = shortener.snapshot();
			run.context().put("productSnapshotBeforeImpl", before.asMap());
		}
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
		if (Boolean.TRUE.equals(plan.get("requireCompilePassed"))) {
			Object drafts = artifact.operations().get("drafts");
			if (!(drafts instanceof List<?> list) || list.stream().noneMatch(d -> d instanceof Map<?, ?> m
					&& Boolean.TRUE.equals(m.get("accepted")))) {
				throw new IllegalStateException("Change validation failed: no accepted compiled draft");
			}
		}
		if (Boolean.TRUE.equals(plan.get("requireDiffPresent"))
				&& (artifact.unifiedDiff() == null || !artifact.unifiedDiff().contains("AliasPolicy"))) {
			throw new IllegalStateException("Change validation failed: real source diff missing");
		}
		if (Boolean.TRUE.equals(plan.get("requireOpenApiPaths"))
				&& (artifact.apiSpecification() == null || !artifact.apiSpecification().contains("/api/urls"))) {
			throw new IllegalStateException("Change validation failed: OpenAPI incomplete");
		}
		String sampleUrl = String.valueOf(run.context().get("sampleUrl"));
		String sampleCode = String.valueOf(run.context().get("sampleCode"));
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

	private Map<String, Object> validateDraft(Path workspace, Path file, String source) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("file", file.toString());
		int policyHits = 0;
		int compileErrors = 0;
		String firstProblem = "";
		if (source.contains("Runtime.getRuntime")) {
			policyHits = 1;
			firstProblem = "[SECURITY] dangerous API Runtime.getRuntime";
		}
		else {
			compileErrors = compile(workspace, file) ? 0 : 1;
			if (compileErrors > 0) {
				firstProblem = "compile failed for " + file.getFileName();
			}
		}
		boolean accepted = policyHits == 0 && compileErrors == 0;
		result.put("accepted", accepted);
		result.put("policy", policyHits);
		result.put("compile", compileErrors);
		result.put("firstProblem", firstProblem);
		return result;
	}

	private boolean compile(Path workspace, Path file) {
		try {
			Path out = workspace.resolve("classes");
			Files.createDirectories(out);
			ProcessBuilder pb = new ProcessBuilder("javac", "-d", out.toString(), file.toString());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			boolean finished = p.waitFor(20, TimeUnit.SECONDS);
			return finished && p.exitValue() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static String brokenAliasPolicy() {
		return """
				package com.agentic.generated;
				public final class AliasPolicy {
				  public static boolean isValid(String alias) {
				    if (alias == null) return false
				    return alias.matches("^[a-zA-Z0-9_-]{3,32}$");
				  }
				}
				""";
	}

	private static String validAliasPolicy(OrchestrationRun run) {
		boolean reserved = run.requirement().toLowerCase().contains("admin")
				|| run.requirement().toLowerCase().contains("reserved")
				|| "brownfield".equals(run.scenario())
				|| Boolean.TRUE.equals(run.context().get("replanApplied"));
		return """
				package com.agentic.generated;
				import java.util.Locale;
				import java.util.Set;
				public final class AliasPolicy {
				  private static final Set<String> RESERVED = Set.of("api", "admin", "h2-console");
				  private AliasPolicy() {}
				  public static boolean isValid(String alias) {
				    if (alias == null || !alias.matches("^[a-zA-Z0-9_-]{3,32}$")) {
				      return false;
				    }
				    %s
				    return true;
				  }
				}
				""".formatted(reserved
				? "if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) { return false; }"
				: "// reserved-word rejection optional for this scenario");
	}
}
