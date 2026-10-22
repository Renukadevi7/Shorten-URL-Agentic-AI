package com.agentic.orchestration;

public enum StageStatus {
	BLOCKED,
	READY,
	RUNNING,
	AWAITING_APPROVAL,
	SUCCEEDED,
	FAILED,
	SKIPPED,
	ROLLED_BACK
}
