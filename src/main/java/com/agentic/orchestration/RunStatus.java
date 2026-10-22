package com.agentic.orchestration;

public enum RunStatus {
	PENDING,
	RUNNING,
	AWAITING_APPROVAL,
	RETRYING,
	ROLLING_BACK,
	SAFE_STOPPED,
	FAILED,
	COMPLETED
}
