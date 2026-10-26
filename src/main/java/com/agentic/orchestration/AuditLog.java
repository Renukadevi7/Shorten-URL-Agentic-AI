package com.agentic.orchestration;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Audit-grade append-only decision lineage for a run.
 */
@Component
public class AuditLog {

	public void record(OrchestrationRun run, String stageId, String kind, String message, Map<String, Object> details) {
		run.audit(stageId, kind, message, details);
	}

	public List<AuditEvent> lineage(OrchestrationRun run) {
		return run.lineage();
	}
}
