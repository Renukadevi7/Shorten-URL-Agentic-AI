package com.agentic.orchestration.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.agentic.orchestration.OrchestrationRun;
import com.agentic.orchestration.PolicyGuardrail;
import com.agentic.shortener.AliasRules;
import com.agentic.shortener.AliasRulesHolder;
import com.agentic.shortener.ProductSnapshot;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

/**
 * ChangeAgent that calls a pluggable {@link ModelClient}, writes real source, compiles it,
 * runs AliasRules unit checks in a <strong>forked JVM with timeout</strong>, repairs on failure,
 * then installs the class into the live product via {@link AliasRulesHolder}.
 */
@Primary
@Component
public class ScriptedModelChangeAgent implements ChangeAgent {

	private static final String RELATIVE = "src/main/java/com/agentic/generated/GeneratedAliasRules.java";
	private static final String FQCN = "com.agentic.generated.GeneratedAliasRules";
	private static final long UNIT_CHECK_TIMEOUT_SEC = 10L;

	private final UrlShortenerService shortener;
	private final PolicyGuardrail policyGuardrail;
	private final ModelClient modelClient;
	private final AliasRulesHolder aliasRulesHolder;
	private final OpenApiFromCodeGenerator openApiFromCodeGenerator;

	public ScriptedModelChangeAgent(UrlShortenerService shortener, PolicyGuardrail policyGuardrail,
			ModelClient modelClient, AliasRulesHolder aliasRulesHolder,
			OpenApiFromCodeGenerator openApiFromCodeGenerator) {
		this.shortener = shortener;
		this.policyGuardrail = policyGuardrail;
		this.modelClient = modelClient;
		this.aliasRulesHolder = aliasRulesHolder;
		this.openApiFromCodeGenerator = openApiFromCodeGenerator;
	}

	@Override
	public ChangeArtifact produce(OrchestrationRun run) {
		String changeId = "chg-" + UUID.randomUUID().toString().substring(0, 8);
		Path workspace = Path.of("data", "agent-workspace", run.id(), changeId);
		try {
			Files.createDirectories(workspace.resolve("src/main/java/com/agentic/generated"));
			Files.createDirectories(workspace.resolve("classes"));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to create agent workspace", ex);
		}

		Path casesFile;
		try {
			casesFile = AliasCaseMaterializer.writeTestFile(workspace, run.requirement());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to materialize alias unit-check file", ex);
		}

		List<Map<String, Object>> drafts = new ArrayList<>();
		String finalSource = null;
		int acceptedDraft = -1;
		Map<String, Object> hints = new LinkedHashMap<>();
		hints.put("replanApplied", run.context().get("replanApplied"));

		for (int draft = 1; draft <= 3; draft++) {
			String source = modelClient.draftSource(RELATIVE, run.requirement(), run.scenario(), draft, hints);
			Path file = workspace.resolve(RELATIVE);
			try {
				Files.writeString(file, source, StandardCharsets.UTF_8);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Unable to write draft", ex);
			}
			Map<String, Object> result = validateDraft(workspace, file, source, casesFile);
			drafts.add(result);
			if (Boolean.TRUE.equals(result.get("accepted"))) {
				finalSource = source;
				acceptedDraft = draft;
				break;
			}
			hints.put("lastProblem", result.get("firstProblem"));
		}
		if (finalSource == null) {
			throw new IllegalStateException("Model failed to produce a compiling, tested change after repairs");
		}

		String diff = """
				diff --git a/%s b/%s
				--- /dev/null
				+++ b/%s
				%s
				""".formatted(RELATIVE, RELATIVE, RELATIVE, finalSource);

		// OpenAPI is derived from live Spring MVC mappings (actual controller code).
		String openApi = openApiFromCodeGenerator.toYaml();

		boolean brownfield = "brownfield".equals(run.scenario())
				|| run.requirement().toLowerCase().contains("analytics");
		boolean reset = !"brownfield".equals(run.scenario()) || Boolean.TRUE.equals(run.context().get("replanApplied"));

		Map<String, Object> operations = new LinkedHashMap<>();
		operations.put("workspace", workspace.toString());
		operations.put("acceptedDraft", acceptedDraft);
		operations.put("drafts", drafts);
		operations.put("modelClient", modelClient.getClass().getSimpleName());
		operations.put("openApiSource", "RequestMappingHandlerMapping");
		operations.put("installAliasRules", true);
		operations.put("generatedClass", FQCN);
		operations.put("aliasCasesFile", casesFile.toString());
		operations.put("unitChecksIsolatedJvm", true);
		operations.put("unitCheckTimeoutSec", UNIT_CHECK_TIMEOUT_SEC);
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
		validation.put("requireUnitTestsPassed", true);
		validation.put("requireAliasRulesInstalled", true);
		validation.put("requireExpiryHonored", true);

		run.context().put("agentDrafts", drafts);
		run.context().put("agentWorkspace", workspace.toString());
		run.context().put("aliasCasesFile", casesFile.toString());

		return new ChangeArtifact(
				changeId,
				"Model change after " + acceptedDraft + " draft(s) via " + modelClient.getClass().getSimpleName(),
				"generate→policy→compile→isolated-test→repair for scenario=" + run.scenario(),
				List.of(RELATIVE, "UrlShortenerService.java", casesFile.toString()),
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
		if (Boolean.TRUE.equals(ops.get("installAliasRules"))) {
			Path classes = Path.of(String.valueOf(ops.get("workspace")), "classes");
			installGeneratedRules(classes);
			run.context().put("aliasRulesInstalled", true);
		}

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
		if (Boolean.TRUE.equals(plan.get("requireCompilePassed"))
				|| Boolean.TRUE.equals(plan.get("requireUnitTestsPassed"))) {
			Object drafts = artifact.operations().get("drafts");
			if (!(drafts instanceof List<?> list) || list.stream().noneMatch(d -> d instanceof Map<?, ?> m
					&& Boolean.TRUE.equals(m.get("accepted")))) {
				throw new IllegalStateException("Change validation failed: no accepted compiled+tested draft");
			}
		}
		if (Boolean.TRUE.equals(plan.get("requireAliasRulesInstalled"))
				&& !Boolean.TRUE.equals(run.context().get("aliasRulesInstalled"))) {
			throw new IllegalStateException("Change validation failed: AliasRules not installed into product");
		}
		if (Boolean.TRUE.equals(plan.get("requireDiffPresent"))
				&& (artifact.unifiedDiff() == null || !artifact.unifiedDiff().contains("GeneratedAliasRules"))) {
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

	private Map<String, Object> validateDraft(Path workspace, Path file, String source, Path casesFile) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("file", file.toString());
		result.put("casesFile", casesFile.toString());
		int policyHits = 0;
		int compileErrors = 0;
		int testFailures = 0;
		String firstProblem = "";
		List<String> policy = DraftPolicyScanner.scan(RELATIVE, source);
		if (!policy.isEmpty()) {
			policyHits = policy.size();
			firstProblem = "[SECURITY] " + policy.get(0);
			result.put("policyHits", policy);
		}
		else if (!compile(workspace, file)) {
			compileErrors = 1;
			firstProblem = "compile failed for " + file.getFileName();
		}
		else if (!runIsolatedUnitChecks(workspace.resolve("classes"), casesFile)) {
			testFailures = 1;
			firstProblem = "AliasRules isolated unit checks failed or timed out";
		}
		boolean accepted = policyHits == 0 && compileErrors == 0 && testFailures == 0;
		result.put("accepted", accepted);
		result.put("policy", policyHits);
		result.put("compile", compileErrors);
		result.put("tests", testFailures);
		result.put("isolatedJvm", true);
		result.put("firstProblem", firstProblem);
		return result;
	}

	private boolean compile(Path workspace, Path file) {
		try {
			Path out = workspace.resolve("classes");
			Files.createDirectories(out);
			// Lean CP only — full spring-boot classpath makes javac/fork take minutes on Windows.
			String cp = leanClasspath(out);
			ProcessBuilder pb = new ProcessBuilder("javac", "-cp", cp, "-d", out.toString(), file.toString());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			boolean finished = p.waitFor(30, TimeUnit.SECONDS);
			if (!finished) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	/** Runs checks in a separate JVM so a bad draft cannot hang or System.exit the app. */
	private boolean runIsolatedUnitChecks(Path classesDir, Path casesFile) {
		try {
			String javaHome = System.getProperty("java.home");
			String javaBin = Path.of(javaHome, "bin", "java").toString();
			String cp = leanClasspath(classesDir);
			ProcessBuilder pb = new ProcessBuilder(
					javaBin,
					"-cp", cp,
					AliasRulesIsolatedProbe.class.getName(),
					classesDir.toAbsolutePath().toString(),
					casesFile.toAbsolutePath().toString(),
					FQCN);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			boolean finished = p.waitFor(UNIT_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS);
			if (!finished) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	/**
	 * Minimal classpath for draft javac / probe forks: generated classes + app classes that
	 * define {@link AliasRules} / {@link AliasRulesIsolatedProbe}. Avoids the multi‑thousand‑entry
	 * Spring Boot runtime classpath that made Start orchestration stall for minutes.
	 */
	static String leanClasspath(Path generatedClasses) {
		String sep = System.getProperty("path.separator");
		Set<String> entries = new LinkedHashSet<>();
		if (generatedClasses != null) {
			entries.add(generatedClasses.toAbsolutePath().toString());
		}
		Path targetClasses = Path.of("target", "classes");
		if (Files.isDirectory(targetClasses)) {
			entries.add(targetClasses.toAbsolutePath().toString());
		}
		for (Class<?> type : List.of(AliasRules.class, AliasRulesIsolatedProbe.class)) {
			try {
				var cs = type.getProtectionDomain().getCodeSource();
				if (cs == null || cs.getLocation() == null) {
					continue;
				}
				URI uri = cs.getLocation().toURI();
				entries.add(Path.of(uri).toAbsolutePath().toString());
			}
			catch (Exception ignored) {
				// fall through; target/classes usually covers spring-boot:run
			}
		}
		if (entries.size() <= 1) {
			// Last resort: filtered runtime CP (still avoid dumping every Spring jar when possible).
			String full = System.getProperty("java.class.path", "");
			for (String part : full.split(sep)) {
				String p = part.toLowerCase();
				if (p.contains("agentic") || p.contains("target\\classes") || p.contains("target/classes")) {
					entries.add(part);
				}
			}
		}
		return String.join(sep, entries);
	}

	private void installGeneratedRules(Path classesDir) {
		try {
			aliasRulesHolder.install(loadRules(classesDir));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to install generated AliasRules into product", ex);
		}
	}

	private AliasRules loadRules(Path classesDir) throws Exception {
		URL[] urls = { classesDir.toUri().toURL() };
		URLClassLoader loader = new URLClassLoader(urls, AliasRules.class.getClassLoader());
		Class<?> clazz = Class.forName(FQCN, true, loader);
		Object instance = clazz.getDeclaredConstructor().newInstance();
		if (!(instance instanceof AliasRules rules)) {
			throw new IllegalStateException("Generated class does not implement AliasRules");
		}
		return rules;
	}
}
