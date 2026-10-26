package com.agentic.orchestration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class PolicyGuardrail {

	private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1");
	private static final Set<String> PII_HINTS = Set.of("ssn", "social security", "credit card", "password=");

	public List<String> validateRequirement(String requirement) {
		List<String> violations = new ArrayList<>();
		if (requirement == null || requirement.isBlank()) {
			violations.add("Compliance: requirement text is empty");
			return violations;
		}
		String lower = requirement.toLowerCase(Locale.ROOT);
		if (lower.contains("disable auth forever") || lower.contains("remove authentication")) {
			violations.add("Compliance: cannot permanently disable authentication");
		}
		if (lower.contains("skip security review") || lower.contains("bypass change control")) {
			violations.add("Change-control: cannot bypass mandatory security/change review");
		}
		for (String hint : PII_HINTS) {
			if (lower.contains(hint)) {
				violations.add("Security: requirement appears to embed sensitive/PII material (" + hint + ")");
			}
		}
		if (lower.contains("http://") && !lower.contains("https://")) {
			violations.add("Security: prefer HTTPS-only targets in requirements");
		}
		return violations;
	}

	public List<String> validateRelease(OrchestrationRun run) {
		List<String> violations = new ArrayList<>();
		if (!Boolean.TRUE.equals(run.context().get("testsPassed"))) {
			violations.add("Quality/change-control: tests must pass before release");
		}
		if (!Boolean.TRUE.equals(run.context().get("docsReady"))) {
			violations.add("Change-control: documentation artifact required before release");
		}
		if (!Boolean.TRUE.equals(run.context().get("implementationDone"))) {
			violations.add("Change-control: implementation must be complete");
		}
		Object sample = run.context().get("sampleUrl");
		if (sample instanceof String url) {
			try {
				URI uri = URI.create(url);
				if (!"https".equalsIgnoreCase(uri.getScheme())) {
					violations.add("Security: release sample URL must be HTTPS");
				}
				String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
				if (BLOCKED_HOSTS.contains(host) || host.endsWith(".local")) {
					violations.add("Security: release sample URL host is blocked");
				}
			}
			catch (Exception ex) {
				violations.add("Security: sample URL is not a valid URI");
			}
		}
		else {
			violations.add("Change-control: sampleUrl missing from run context");
		}
		if (Boolean.TRUE.equals(run.context().get("policyDirty"))) {
			violations.add("Compliance: unresolved policyDirty flag in context");
		}
		return violations;
	}

	public List<String> validateUrlMutation(String url, String alias) {
		List<String> violations = new ArrayList<>();
		if (alias != null && alias.toLowerCase(Locale.ROOT).contains("admin")) {
			violations.add("Security: alias containing 'admin' is reserved");
		}
		if (url != null) {
			String lower = url.toLowerCase(Locale.ROOT);
			if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
				violations.add("Security: dangerous URL scheme blocked");
			}
		}
		return violations;
	}
}
