package com.agentic.agent;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StepStatus {
	RUNNING("running"),
	DONE("done"),
	ERROR("error");

	private final String json;

	StepStatus(String json) {
		this.json = json;
	}

	@JsonValue
	public String json() {
		return json;
	}
}
