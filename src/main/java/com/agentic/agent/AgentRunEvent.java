package com.agentic.agent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunEvent(
		String type,
		AgentStep step,
		String answer,
		List<AgentStep> steps,
		String message
) {
	public static AgentRunEvent step(AgentStep step) {
		return new AgentRunEvent("step", step, null, null, null);
	}

	public static AgentRunEvent done(String answer, List<AgentStep> steps) {
		return new AgentRunEvent("done", null, answer, steps, null);
	}

	public static AgentRunEvent error(String message) {
		return new AgentRunEvent("error", null, null, null, message);
	}
}
