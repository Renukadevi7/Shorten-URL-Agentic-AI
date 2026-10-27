package com.agentic.orchestration;

@FunctionalInterface
public interface StageHandler {
	void execute(OrchestrationRun run, StageNode stage);
}
