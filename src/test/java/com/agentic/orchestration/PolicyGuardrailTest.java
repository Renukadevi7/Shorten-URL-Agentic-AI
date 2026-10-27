package com.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PolicyGuardrailTest {

	private final PolicyGuardrail guardrail = new PolicyGuardrail();

	@Test
	void blocksAuthRemovalAndRecordsAllowBlockOutcomes() {
		List<PolicyDecision> decisions = guardrail.evaluateRequirement("Please remove authentication forever");
		assertTrue(decisions.stream().anyMatch(d -> "AUTH_REQUIRED".equals(d.rule()) && !d.allowed()));
		assertTrue(guardrail.auditBundle(decisions).get("blocked") instanceof List<?> blocked && !blocked.isEmpty());
	}

	@Test
	void allowsCleanRequirement() {
		List<PolicyDecision> decisions = guardrail
				.evaluateRequirement("Build a URL shortener with create and redirect APIs over HTTPS.");
		assertTrue(decisions.stream().allMatch(PolicyDecision::allowed));
		assertFalse(decisions.isEmpty());
	}

	@Test
	void blocksReservedAdminAlias() {
		List<PolicyDecision> decisions = guardrail.evaluateUrlMutation("https://example.com", "admin-link");
		assertTrue(decisions.stream().anyMatch(d -> "ALIAS_RESERVED".equals(d.rule()) && !d.allowed()));
	}
}
