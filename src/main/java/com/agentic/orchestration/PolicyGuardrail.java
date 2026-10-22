package com.agentic.orchestration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class PolicyGuardrail {

	public List<String> validateRequirement(String requirement) {
		List<String> violations = new ArrayList<>();
		if (requirement == null || requirement.isBlank()) {
			violations.add("Requirement text is empty");
		}
		if (requirement != null && requirement.toLowerCase().contains("disable auth forever")) {
			violations.add("Compliance: cannot permanently disable authentication");
		}
		return violations;
	}

	public List<String> validateRelease(OrchestrationRun run) {
		List<String> violations = new ArrayList<>();
		Object sample = run.context().get("sampleUrl");
		if (sample instanceof String url) {
			try {
				URI uri = URI.create(url);
				if (!"https".equalsIgnoreCase(uri.getScheme())) {
					violations.add("Change-control: release checklist prefers HTTPS sample URLs");
				}
			}
			catch (Exception ex) {
				violations.add("Security: sample URL is not a valid URI");
			}
		}
		if (!Boolean.TRUE.equals(run.context().get("testsPassed"))) {
			violations.add("Quality gate: tests must pass before release");
		}
		return violations;
	}
}
