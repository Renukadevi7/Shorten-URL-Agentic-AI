package com.agentic.agent;

public record AgentStep(
		String id,
		AgentStepKind kind,
		String title,
		String detail,
		String toolName,
		StepStatus status,
		long at
) {
	public AgentStep withStatus(StepStatus status) {
		return new AgentStep(id, kind, title, detail, toolName, status, at);
	}

	public AgentStep withDetail(String detail) {
		return new AgentStep(id, kind, title, detail, toolName, status, at);
	}

	public AgentStep withDetailAndStatus(String detail, StepStatus status) {
		return new AgentStep(id, kind, title, detail, toolName, status, at);
	}
}
