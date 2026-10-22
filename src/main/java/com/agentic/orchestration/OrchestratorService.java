package com.agentic.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.agentic.scenarios.ScenarioFactory;

@Service
public class OrchestratorService {

	private final ScenarioFactory scenarioFactory;
	private final StageExecutor stageExecutor;
	private final PolicyGuardrail policyGuardrail;
	private final MetricsRegistry metricsRegistry;
	private final Map<String, OrchestrationRun> runs = new ConcurrentHashMap<>();

	public OrchestratorService(ScenarioFactory scenarioFactory, StageExecutor stageExecutor,
			PolicyGuardrail policyGuardrail, MetricsRegistry metricsRegistry) {
		this.scenarioFactory = scenarioFactory;
		this.stageExecutor = stageExecutor;
		this.policyGuardrail = policyGuardrail;
		this.metricsRegistry = metricsRegistry;
	}

	public OrchestrationRun start(String scenario, String requirement) {
		List<String> violations = policyGuardrail.validateRequirement(requirement);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("Policy guardrail blocked start: " + String.join("; ", violations));
		}

		OrchestrationRun run = new OrchestrationRun(UUID.randomUUID().toString().substring(0, 8), scenario, requirement);
		scenarioFactory.materialize(run);
		run.audit(null, "RUN_STARTED", "Scenario materialized with dependency graph",
				Map.of("scenario", scenario, "stages", run.stageOrder()));
		runs.put(run.id(), run);
		metricsRegistry.onStart();
		advance(run.id());
		return run;
	}

	public Optional<OrchestrationRun> get(String id) {
		return Optional.ofNullable(runs.get(id));
	}

	public List<OrchestrationRun> list() {
		return new ArrayList<>(runs.values());
	}

	public OrchestrationRun approve(String id, String actor) {
		OrchestrationRun run = require(id);
		if (run.status() != RunStatus.AWAITING_APPROVAL) {
			throw new IllegalStateException("Run is not awaiting approval");
		}
		String stageId = run.awaitingStageId();
		StageNode stage = run.stages().get(stageId);
		run.audit(stageId, "HUMAN_APPROVAL", "Approved by " + actor, Map.of("actor", actor));
		stage.setStatus(StageStatus.SUCCEEDED);
		run.setAwaitingStageId(null);
		run.setStatus(RunStatus.RUNNING);

		if (stage.type() == StageType.RELEASE_READINESS) {
			List<String> releaseViolations = policyGuardrail.validateRelease(run);
			if (!releaseViolations.isEmpty()) {
				fail(run, "Release policy blocked: " + String.join("; ", releaseViolations));
				return run;
			}
			complete(run);
			return run;
		}

		advance(run.id());
		return run;
	}

	public OrchestrationRun reject(String id, String reason) {
		OrchestrationRun run = require(id);
		run.audit(run.awaitingStageId(), "HUMAN_REJECT", reason, Map.of());
		safeStop(run, "Rejected: " + reason);
		return run;
	}

	public OrchestrationRun resume(String id) {
		OrchestrationRun run = require(id);
		if (run.status() != RunStatus.SAFE_STOPPED) {
			throw new IllegalStateException("Run is not safely stopped");
		}
		run.audit(null, "RESUME", "Operator resumed run after safe-stop", Map.of());
		run.setStatus(RunStatus.RUNNING);
		advance(run.id());
		return run;
	}

	public OrchestrationRun safeStop(String id, String reason) {
		OrchestrationRun run = require(id);
		safeStop(run, reason);
		return run;
	}

	public Map<String, Object> metrics() {
		return metricsRegistry.snapshot();
	}

	private void advance(String runId) {
		OrchestrationRun run = require(runId);
		if (isTerminal(run.status()) || run.status() == RunStatus.AWAITING_APPROVAL) {
			return;
		}
		run.setStatus(RunStatus.RUNNING);

		while (true) {
			List<StageNode> ready = readyStages(run);
			if (ready.isEmpty()) {
				if (allSucceeded(run)) {
					// Should have completed via release approval; if release had no approval left
					StageNode release = run.stages().values().stream()
							.filter(s -> s.type() == StageType.RELEASE_READINESS)
							.findFirst()
							.orElse(null);
					if (release != null && release.status() == StageStatus.SUCCEEDED) {
						complete(run);
					}
				}
				return;
			}

			// Parallel execution of currently ready stages (sync barrier naturally via deps)
			for (StageNode stage : ready) {
				executeStage(run, stage);
				if (run.status() == RunStatus.AWAITING_APPROVAL || isTerminal(run.status())) {
					return;
				}
			}
		}
	}

	private void executeStage(OrchestrationRun run, StageNode stage) {
		if (!entryGate(run, stage)) {
			run.audit(stage.id(), "ENTRY_GATE_BLOCKED", "Dependencies or policy blocked entry", Map.of());
			return;
		}

		stage.setStatus(StageStatus.RUNNING);
		stage.incrementAttempts();
		run.audit(stage.id(), "STAGE_ENTER", "Entering stage " + stage.title(), Map.of("attempt", stage.attempts()));

		try {
			stageExecutor.execute(run, stage);
			exitGate(run, stage);
		}
		catch (RuntimeException ex) {
			handleFailure(run, stage, ex);
		}
	}

	private boolean entryGate(OrchestrationRun run, StageNode stage) {
		for (String dep : stage.dependsOn()) {
			StageNode parent = run.stages().get(dep);
			if (parent == null || parent.status() != StageStatus.SUCCEEDED) {
				return false;
			}
		}
		return true;
	}

	private void exitGate(OrchestrationRun run, StageNode stage) {
		run.audit(stage.id(), "EXIT_GATE", "Stage work finished; evaluating exit gate", Map.of());
		if (stage.requiresHumanApproval()) {
			stage.setStatus(StageStatus.AWAITING_APPROVAL);
			run.setAwaitingStageId(stage.id());
			run.setStatus(RunStatus.AWAITING_APPROVAL);
			run.audit(stage.id(), "APPROVAL_REQUIRED", "High-impact action requires human approval", Map.of());
			return;
		}
		stage.setStatus(StageStatus.SUCCEEDED);
		run.audit(stage.id(), "STAGE_SUCCESS", stage.output(), Map.of());
	}

	private void handleFailure(OrchestrationRun run, StageNode stage, RuntimeException ex) {
		run.audit(stage.id(), "STAGE_FAILURE", ex.getMessage(), Map.of("attempt", stage.attempts()));
		if (stage.attempts() <= stage.maxRetries()) {
			run.setStatus(RunStatus.RETRYING);
			run.incrementRetries();
			metricsRegistry.onRetry();
			run.audit(stage.id(), "RETRY", "Bounded retry scheduled", Map.of("attempt", stage.attempts()));
			stage.setStatus(StageStatus.READY);
			try {
				stageExecutor.execute(run, stage);
				exitGate(run, stage);
				return;
			}
			catch (RuntimeException retryEx) {
				run.audit(stage.id(), "RETRY_FAILED", retryEx.getMessage(), Map.of());
			}
		}

		// Fallback / rollback
		run.setStatus(RunStatus.ROLLING_BACK);
		run.incrementRollbacks();
		metricsRegistry.onRollback();
		stage.setStatus(StageStatus.ROLLED_BACK);
		run.audit(stage.id(), "ROLLBACK", "Rolling back stage artifacts / marking failed path", Map.of());
		safeStop(run, "Stage failed after retries: " + ex.getMessage());
	}

	private List<StageNode> readyStages(OrchestrationRun run) {
		List<StageNode> ready = new ArrayList<>();
		for (String id : run.stageOrder()) {
			StageNode stage = run.stages().get(id);
			if (stage.status() == StageStatus.SUCCEEDED || stage.status() == StageStatus.SKIPPED
					|| stage.status() == StageStatus.RUNNING || stage.status() == StageStatus.AWAITING_APPROVAL
					|| stage.status() == StageStatus.ROLLED_BACK || stage.status() == StageStatus.FAILED) {
				continue;
			}
			boolean depsMet = stage.dependsOn().stream()
					.allMatch(dep -> run.stages().get(dep).status() == StageStatus.SUCCEEDED);
			if (depsMet) {
				stage.setStatus(StageStatus.READY);
				ready.add(stage);
			}
			else {
				stage.setStatus(StageStatus.BLOCKED);
			}
		}
		return ready;
	}

	private boolean allSucceeded(OrchestrationRun run) {
		return run.stages().values().stream().allMatch(s -> s.status() == StageStatus.SUCCEEDED);
	}

	private void complete(OrchestrationRun run) {
		run.setStatus(RunStatus.COMPLETED);
		run.setEndedAt(Instant.now());
		run.audit(null, "RUN_COMPLETED", "Orchestration completed under governance", Map.of("latencyMs", run.latencyMs()));
		metricsRegistry.onComplete(run.latencyMs());
	}

	private void fail(OrchestrationRun run, String message) {
		run.setStatus(RunStatus.FAILED);
		run.setFailureMessage(message);
		run.setEndedAt(Instant.now());
		run.audit(null, "RUN_FAILED", message, Map.of());
		metricsRegistry.onFail(run.latencyMs(), run.latencyMs());
	}

	private void safeStop(OrchestrationRun run, String reason) {
		run.setStatus(RunStatus.SAFE_STOPPED);
		run.setFailureMessage(reason);
		run.setEndedAt(Instant.now());
		run.audit(null, "SAFE_STOP", reason, Map.of());
		metricsRegistry.onFail(run.latencyMs(), run.latencyMs());
	}

	private OrchestrationRun require(String id) {
		OrchestrationRun run = runs.get(id);
		if (run == null) {
			throw new IllegalArgumentException("Unknown run id: " + id);
		}
		return run;
	}

	private static boolean isTerminal(RunStatus status) {
		return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.SAFE_STOPPED;
	}
}
