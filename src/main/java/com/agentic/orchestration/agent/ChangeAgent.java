package com.agentic.orchestration.agent;

import com.agentic.orchestration.OrchestrationRun;

/**
 * Pluggable change producer. Implementations may be heuristic or LLM-backed.
 * Contract: produce → apply → validate.
 */
public interface ChangeAgent {

	ChangeArtifact produce(OrchestrationRun run);

	void apply(OrchestrationRun run, ChangeArtifact artifact);

	void validate(OrchestrationRun run, ChangeArtifact artifact);
}
