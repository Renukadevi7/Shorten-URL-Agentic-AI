package com.agentic.agent;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AgentStepKind {
	PLAN("plan"),
	TOOL("tool"),
	OBSERVE("observe"),
	ANSWER("answer"),
	ERROR("error");

	private final String json;

	AgentStepKind(String json) {
		this.json = json;
	}

	@JsonValue
	public String json() {
		return json;
	}
}
