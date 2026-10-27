package com.agentic.orchestration;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class PolicyGuardrail {

	private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1");
	private static final Set<String> PII_HINTS = Set.of("ssn", "social security", "credit card", "password=");

	public List<String> validateRequirement(String requirement) {
		List<PolicyDecision> decisions = evaluateRequirement(requirement);
		return decisions.stream().filter(d -> !d.allowed()).map(PolicyDecision::reason).toList();
	}

	public List<PolicyDecision> evaluateRequirement(String requirement) {
		List<PolicyDecision> decisions = new ArrayList<>();
		if (requirement == null || requirement.isBlank()) {
			decisions.add(block("REQ_NON_EMPTY", "Compliance: requirement text is empty"));
			return decisions;
		}
		String lower = requirement.toLowerCase(Locale.ROOT);
		if (lower.contains("disable auth forever") || lower.contains("remove authentication")) {
			decisions.add(block("AUTH_REQUIRED", "Compliance: cannot permanently disable authentication"));
		}
		else {
			decisions.add(allow("AUTH_REQUIRED", "Authentication remains required"));
		}
		if (lower.contains("skip security review") || lower.contains("bypass change control")) {
			decisions.add(block("CHANGE_CONTROL", "Change-control: cannot bypass mandatory security/change review"));
		}
		else {
			decisions.add(allow("CHANGE_CONTROL", "Change-control gates remain enforced"));
		}
		boolean pii = false;
		for (String hint : PII_HINTS) {
			if (lower.contains(hint)) {
				decisions.add(block("PROTECTED_DATA", "Security: requirement embeds sensitive/PII material (" + hint + ")"));
				pii = true;
				break;
			}
		}
		if (!pii) {
			decisions.add(allow("PROTECTED_DATA", "No protected-data markers detected in requirement"));
		}
		if (lower.contains("http://") && !lower.contains("https://")) {
			decisions.add(block("HTTPS_ONLY", "Security: prefer HTTPS-only targets in requirements"));
		}
		else {
			decisions.add(allow("HTTPS_ONLY", "Requirement does not mandate insecure HTTP-only targets"));
		}
		return decisions;
	}

	public List<String> validateRelease(OrchestrationRun run) {
		return evaluateRelease(run).stream().filter(d -> !d.allowed()).map(PolicyDecision::reason).toList();
	}

	public List<PolicyDecision> evaluateRelease(OrchestrationRun run) {
		List<PolicyDecision> decisions = new ArrayList<>();
		// Gate keys come from the completesRun stage metadata (entry/consumes), not a hard-coded list.
		StageNode release = run.stages().values().stream()
				.filter(StageNode::completesRun)
				.findFirst()
				.orElse(null);
		if (release == null) {
			decisions.add(block("RELEASE_STAGE", "Change-control: no completesRun stage in graph"));
		}
		else {
			java.util.LinkedHashSet<String> gates = new java.util.LinkedHashSet<>();
			gates.addAll(release.requiredEntryContext());
			gates.addAll(release.consumesContext());
			if (gates.isEmpty()) {
				decisions.add(block("RELEASE_GATES", "Change-control: release stage declares no entry/consumes gates"));
			}
			for (String key : gates) {
				decisions.add(boolCtx(run, key, "RELEASE_GATE_" + key,
						"Change-control: required context '" + key + "' must be true before release",
						"Release gate satisfied: " + key));
			}
		}

		Object sample = run.context().get("sampleUrl");
		if (sample instanceof String url) {
			try {
				URI uri = URI.create(url);
				if (!"https".equalsIgnoreCase(uri.getScheme())) {
					decisions.add(block("RELEASE_HTTPS", "Security: release sample URL must be HTTPS"));
				}
				else {
					decisions.add(allow("RELEASE_HTTPS", "Release sample URL uses HTTPS"));
				}
				String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
				if (BLOCKED_HOSTS.contains(host) || host.endsWith(".local")) {
					decisions.add(block("RELEASE_HOST", "Security: release sample URL host is blocked"));
				}
				else {
					decisions.add(allow("RELEASE_HOST", "Release sample host is allowed"));
				}
			}
			catch (Exception ex) {
				decisions.add(block("RELEASE_URI", "Security: sample URL is not a valid URI"));
			}
		}
		else {
			decisions.add(block("RELEASE_SAMPLE", "Change-control: sampleUrl missing from run context"));
		}
		if (Boolean.TRUE.equals(run.context().get("policyDirty"))) {
			decisions.add(block("POLICY_DIRTY", "Compliance: unresolved policyDirty flag in context"));
		}
		else {
			decisions.add(allow("POLICY_DIRTY", "No unresolved policyDirty flag"));
		}
		return decisions;
	}

	public List<String> validateUrlMutation(String url, String alias) {
		return evaluateUrlMutation(url, alias).stream().filter(d -> !d.allowed()).map(PolicyDecision::reason).toList();
	}

	public List<PolicyDecision> evaluateUrlMutation(String url, String alias) {
		List<PolicyDecision> decisions = new ArrayList<>();
		if (alias != null && alias.toLowerCase(Locale.ROOT).contains("admin")) {
			decisions.add(block("ALIAS_RESERVED", "Security: alias containing 'admin' is reserved"));
		}
		else {
			decisions.add(allow("ALIAS_RESERVED", "Alias is not a reserved admin token"));
		}
		if (url != null) {
			String lower = url.toLowerCase(Locale.ROOT);
			if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
				decisions.add(block("URL_SCHEME", "Security: dangerous URL scheme blocked"));
			}
			else {
				decisions.add(allow("URL_SCHEME", "URL scheme is acceptable"));
			}
		}
		return decisions;
	}

	public Map<String, Object> auditBundle(List<PolicyDecision> decisions) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("decisions", decisions.stream().map(PolicyDecision::asMap).toList());
		map.put("blocked", decisions.stream().filter(d -> !d.allowed()).map(PolicyDecision::rule).toList());
		map.put("allowed", decisions.stream().filter(PolicyDecision::allowed).map(PolicyDecision::rule).toList());
		return map;
	}

	private PolicyDecision boolCtx(OrchestrationRun run, String key, String rule, String blockMsg, String allowMsg) {
		if (Boolean.TRUE.equals(run.context().get(key))) {
			return allow(rule, allowMsg);
		}
		return block(rule, blockMsg);
	}

	private static PolicyDecision allow(String rule, String reason) {
		return new PolicyDecision(rule, true, reason);
	}

	private static PolicyDecision block(String rule, String reason) {
		return new PolicyDecision(rule, false, reason);
	}
}
