package com.agentic.orchestration;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
		Instant at,
		String runId,
		String stageId,
		String kind,
		String message,
		Map<String, Object> details
) {
}
