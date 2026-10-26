package com.agentic.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import com.agentic.scenarios.ScenarioFactory;
import com.agentic.shortener.ProductSnapshot;
import com.agentic.shortener.UrlShortenerService;

@Service
public class OrchestratorService {

	private final ScenarioFactory scenarioFactory;
	private final StageExecutor stageExecutor;
	private final GatePolicy gatePolicy;
	private final PolicyGuardrail policyGuardrail;
	private final AuditLog auditLog;
	private final MetricsRegistry metricsRegistry;
	private final UrlShortenerService shortener;
	private final Map<String, OrchestrationRun> runs = new ConcurrentHashMap<>();
	private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "orch-parallel");
		t.setDaemon(true);
		return t;
	});

	public OrchestratorService(ScenarioFactory scenarioFactory, StageExecutor stageExecutor, GatePolicy gatePolicy,
			PolicyGuardrail policyGuardrail, AuditLog auditLog, MetricsRegistry metricsRegistry,
			UrlShortenerService shortener) {
		this.scenarioFactory = scenarioFactory;
		this.stageExecutor = stageExecutor;
		this.gatePolicy = gatePolicy;
		this.policyGuardrail = policyGuardrail;
		this.auditLog = auditLog;
		this.metricsRegistry = metricsRegistry;
		this.shortener = shortener;
	}

	public OrchestrationRun start(String scenario, String requirement) {
		List<String> violations = policyGuardrail.validateRequirement(requirement);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("Policy guardrail blocked start: " + String.join("; ", violations));
		}

		OrchestrationRun run = new OrchestrationRun(UUID.randomUUID().toString().substring(0, 8), scenario, requirement);
		scenarioFactory.materialize(run);
		run.context().put("requirementFingerprint", fingerprint(requirement));
		auditLog.record(run, null, "RUN_STARTED", "Scenario materialized with dependency graph",
				Map.of("scenario", scenario, "graph", run.context().get("stageGraph")));
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
		List<String> exitViolations = gatePolicy.exitViolations(run, stage);
		if (!exitViolations.isEmpty()) {
			throw new IllegalStateException("Exit gate still failing: " + String.join("; ", exitViolations));
		}
		auditLog.record(run, stageId, "HUMAN_APPROVAL", "Approved by " + actor, Map.of("actor", actor));
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
		auditLog.record(run, run.awaitingStageId(), "HUMAN_REJECT", reason, Map.of());
		safeStop(run, "Rejected: " + reason);
		return run;
	}

	public OrchestrationRun resume(String id) {
		OrchestrationRun run = require(id);
		if (run.status() != RunStatus.SAFE_STOPPED) {
			throw new IllegalStateException("Run is not safely stopped");
		}
		auditLog.record(run, null, "RESUME", "Operator resumed run after safe-stop", Map.of());
		run.setStatus(RunStatus.RUNNING);
		run.setEndedAt(null);
		advance(run.id());
		return run;
	}

	public OrchestrationRun safeStop(String id, String reason) {
		OrchestrationRun run = require(id);
		safeStop(run, reason);
		return run;
	}

	/**
	 * Fault injection so scenarios can demonstrate retry/fallback/rollback.
	 */
	public OrchestrationRun injectFault(String id, String faultType, boolean once) {
		OrchestrationRun run = require(id);
		run.context().put("faultInjection", faultType);
		run.context().put("faultOnce", once);
		auditLog.record(run, null, "FAULT_INJECTION", "Injected fault " + faultType, Map.of("once", once));
		return run;
	}

	/**
	 * Mutate an upstream output and trigger dynamic re-planning (not a hard-coded clarification stage).
	 */
	public OrchestrationRun mutateUpstream(String id, String newRequirement) {
		OrchestrationRun run = require(id);
		String oldFp = String.valueOf(run.context().get("requirementFingerprint"));
		run.context().put("previousRequirement", run.requirement());
		// reflect upstream change in context + fingerprint
		run.context().put("upstreamRequirement", newRequirement);
		run.context().put("requirementFingerprint", fingerprint(newRequirement));
		run.context().put("replanReason", "Upstream requirement fingerprint changed: " + oldFp + " -> "
				+ run.context().get("requirementFingerprint"));
		auditLog.record(run, null, "UPSTREAM_CHANGED", "Upstream output/requirement changed; scheduling replan",
				Map.of("oldFingerprint", oldFp, "newFingerprint", run.context().get("requirementFingerprint")));
		dynamicReplan(run);
		if (run.status() != RunStatus.AWAITING_APPROVAL && !isTerminal(run.status())) {
			run.setStatus(RunStatus.RUNNING);
			advance(run.id());
		}
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
			if (shouldReplan(run)) {
				dynamicReplan(run);
			}
			List<StageNode> ready = readyStages(run);
			if (ready.isEmpty()) {
				return;
			}

			auditLog.record(run, null, "PARALLEL_WAVE",
					"Executing ready stages concurrently with sync barrier",
					Map.of("stageIds", ready.stream().map(StageNode::id).toList(), "threadPool", 4));

			List<CompletableFuture<Void>> futures = new ArrayList<>();
			for (StageNode stage : ready) {
				futures.add(CompletableFuture.runAsync(() -> executeStage(run, stage), parallelExecutor));
			}
			// Real synchronization point: wait for the entire ready-set wave.
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

			if (run.status() == RunStatus.AWAITING_APPROVAL || isTerminal(run.status())) {
				return;
			}
		}
	}

	private void executeStage(OrchestrationRun run, StageNode stage) {
		synchronized (run) {
			List<String> entryViolations = gatePolicy.entryViolations(run, stage);
			if (!entryViolations.isEmpty()) {
				auditLog.record(run, stage.id(), "ENTRY_GATE_BLOCKED",
						"Entry gate failed output validation", Map.of("violations", entryViolations));
				stage.setStatus(StageStatus.BLOCKED);
				return;
			}

			stage.setStatus(StageStatus.RUNNING);
			stage.incrementAttempts();
			auditLog.record(run, stage.id(), "STAGE_ENTER",
					"Entering stage " + stage.title() + " on " + Thread.currentThread().getName(),
					Map.of("attempt", stage.attempts()));
		}

		try {
			// Execute outside the run lock so independent stages can proceed concurrently.
			stageExecutor.execute(run, stage);
			synchronized (run) {
				exitGate(run, stage);
			}
		}
		catch (RuntimeException ex) {
			synchronized (run) {
				handleFailure(run, stage, ex);
			}
		}
	}

	private void exitGate(OrchestrationRun run, StageNode stage) {
		List<String> exitViolations = gatePolicy.exitViolations(run, stage);
		auditLog.record(run, stage.id(), "EXIT_GATE", "Evaluating exit gate against stage outputs",
				Map.of("violations", exitViolations));
		if (!exitViolations.isEmpty()) {
			throw new IllegalStateException("Exit gate failed: " + String.join("; ", exitViolations));
		}
		if (stage.requiresHumanApproval()) {
			stage.setStatus(StageStatus.AWAITING_APPROVAL);
			run.setAwaitingStageId(stage.id());
			run.setStatus(RunStatus.AWAITING_APPROVAL);
			auditLog.record(run, stage.id(), "APPROVAL_REQUIRED",
					"High-impact action requires human approval after exit validation", Map.of());
			return;
		}
		stage.setStatus(StageStatus.SUCCEEDED);
		auditLog.record(run, stage.id(), "STAGE_SUCCESS", stage.output(), Map.of());
	}

	private void handleFailure(OrchestrationRun run, StageNode stage, RuntimeException ex) {
		auditLog.record(run, stage.id(), "STAGE_FAILURE", ex.getMessage(), Map.of("attempt", stage.attempts()));
		if (stage.attempts() <= stage.maxRetries()) {
			run.setStatus(RunStatus.RETRYING);
			run.incrementRetries();
			metricsRegistry.onRetry();
			auditLog.record(run, stage.id(), "RETRY", "Bounded retry after failure",
					Map.of("attempt", stage.attempts(), "maxRetries", stage.maxRetries()));
			try {
				stageExecutor.execute(run, stage);
				exitGate(run, stage);
				if (run.status() != RunStatus.AWAITING_APPROVAL) {
					run.setStatus(RunStatus.RUNNING);
				}
				return;
			}
			catch (RuntimeException retryEx) {
				auditLog.record(run, stage.id(), "RETRY_FAILED", retryEx.getMessage(), Map.of());
			}
		}

		if (stage.fallbackStageId() != null) {
			StageNode fallback = run.stages().get(stage.fallbackStageId());
			if (fallback != null) {
				stage.setStatus(StageStatus.FAILED);
				auditLog.record(run, stage.id(), "FALLBACK_ACTIVATED",
						"Primary stage exhausted retries; activating fallback " + fallback.id(), Map.of());
				// release dependency: treat primary as skipped for graph progress via fallback success
				run.context().put("fallbackFor_" + stage.id(), fallback.id());
				fallback.setStatus(StageStatus.READY);
				fallback.resetAttempts();
				try {
					executeStage(run, fallback);
					if (fallback.status() == StageStatus.SUCCEEDED) {
						// allow release deps that required primary test to accept fallback
						run.context().put("acceptedFallback_" + stage.id(), true);
						stage.setStatus(StageStatus.SKIPPED);
						run.setStatus(RunStatus.RUNNING);
						return;
					}
				}
				catch (RuntimeException fallbackEx) {
					auditLog.record(run, fallback.id(), "FALLBACK_FAILED", fallbackEx.getMessage(), Map.of());
				}
			}
		}

		performRollback(run, stage);
		safeStop(run, "Stage failed after retries/fallback: " + ex.getMessage());
	}

	private void performRollback(OrchestrationRun run, StageNode stage) {
		run.setStatus(RunStatus.ROLLING_BACK);
		run.incrementRollbacks();
		metricsRegistry.onRollback();
		Object raw = run.context().get("productSnapshotBeforeImpl");
		if (raw instanceof Map<?, ?> map) {
			try {
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> urls = (List<Map<String, Object>>) map.get("urls");
				ProductSnapshot snapshot = new ProductSnapshot(
						Boolean.TRUE.equals(map.get("analyticsEnabled")),
						Boolean.TRUE.equals(map.get("reliabilityEnabled")),
						urls == null ? List.of() : urls);
				shortener.restore(snapshot);
				auditLog.record(run, stage.id(), "ROLLBACK",
						"Restored product snapshot to pre-implementation state",
						Map.of("restoredUrlCount", snapshot.urls().size()));
			}
			catch (Exception ex) {
				auditLog.record(run, stage.id(), "ROLLBACK_ERROR", ex.getMessage(), Map.of());
			}
		}
		else {
			auditLog.record(run, stage.id(), "ROLLBACK",
					"No product snapshot available; marked stage rolled back only", Map.of());
		}
		stage.setStatus(StageStatus.ROLLED_BACK);
		run.context().put("implementationDone", false);
		run.context().put("testsPassed", false);
	}

	private boolean shouldReplan(OrchestrationRun run) {
		if (Boolean.TRUE.equals(run.context().get("replanScheduled"))) {
			return false;
		}
		Object pending = run.context().get("replanReason");
		Object applied = run.context().get("replanApplied");
		return pending != null && !Boolean.TRUE.equals(applied);
	}

	/**
	 * Dynamic re-plan: insert a REPLAN stage after the last succeeded stage and invalidate downstream.
	 */
	private void dynamicReplan(OrchestrationRun run) {
		String reason = String.valueOf(run.context().getOrDefault("replanReason", "Upstream changed"));
		String replanId = "replan_" + run.stages().size();
		String anchor = run.stageOrder().stream()
				.filter(id -> run.stages().get(id).status() == StageStatus.SUCCEEDED)
				.reduce((a, b) -> b)
				.orElse("req");

		StageNode replan = StageNode.of(replanId, StageType.REPLAN, "Dynamic re-plan after upstream change",
				List.of(anchor), false);
		run.addStage(replan);
		replan.setStatus(StageStatus.READY);

		for (String id : run.stageOrder()) {
			StageNode node = run.stages().get(id);
			if (node.status() == StageStatus.SUCCEEDED || node.id().equals(replanId)) {
				continue;
			}
			if (node.dependsOn().contains(anchor) || node.type() == StageType.ARCHITECTURE
					|| node.type() == StageType.IMPLEMENTATION || node.type() == StageType.TESTING
					|| node.type() == StageType.DOCUMENTATION || node.type() == StageType.RELEASE_READINESS
					|| node.type() == StageType.TESTING_FALLBACK) {
				if (node.status() != StageStatus.BLOCKED) {
					node.setStatus(StageStatus.BLOCKED);
					node.resetAttempts();
				}
			}
		}
		// Force architecture/implementation to depend conceptually on replan by resetting flags
		run.context().put("architectureReady", false);
		run.context().put("implementationDone", false);
		run.context().put("testsPassed", false);
		run.context().put("docsReady", false);
		run.context().remove("replanApplied");

		// Rewire: make architecture depend on replan if present
		StageNode arch = run.stages().get("arch");
		if (arch != null) {
			StageNode rewired = StageNode.of(arch.id(), arch.type(), arch.title(), List.of(replanId),
					arch.requiresHumanApproval(), arch.fallbackStageId());
			run.stages().put(arch.id(), rewired);
		}

		run.context().put("replanScheduled", true);
		auditLog.record(run, replanId, "DYNAMIC_REPLAN",
				"Inserted REPLAN stage and invalidated downstream after upstream change",
				Map.of("reason", reason, "anchor", anchor));
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
			// Special-case: test_fallback stays blocked unless activated via fallback
			if (stage.type() == StageType.TESTING_FALLBACK
					&& !Boolean.TRUE.equals(run.context().get("acceptedFallback_test"))
					&& stage.status() != StageStatus.READY) {
				// only runnable when explicitly set READY by fallback activation
				if (stage.status() != StageStatus.READY) {
					stage.setStatus(StageStatus.BLOCKED);
					continue;
				}
			}
			boolean depsMet = stage.dependsOn().stream().allMatch(dep -> {
				StageNode parent = run.stages().get(dep);
				if (parent == null) {
					return false;
				}
				if (parent.status() == StageStatus.SUCCEEDED || parent.status() == StageStatus.SKIPPED) {
					return true;
				}
				// allow release to proceed if test skipped due to successful fallback
				if ("test".equals(dep) && stage.id().equals("release")
						&& Boolean.TRUE.equals(run.context().get("acceptedFallback_test"))
						&& run.stages().containsKey("test_fallback")
						&& run.stages().get("test_fallback").status() == StageStatus.SUCCEEDED) {
					return true;
				}
				return false;
			});
			if (depsMet && stage.type() != StageType.TESTING_FALLBACK) {
				stage.setStatus(StageStatus.READY);
				ready.add(stage);
			}
			else if (depsMet && stage.status() == StageStatus.READY) {
				ready.add(stage);
			}
			else if (stage.type() != StageType.TESTING_FALLBACK) {
				stage.setStatus(StageStatus.BLOCKED);
			}
		}
		return ready;
	}

	private void complete(OrchestrationRun run) {
		run.setStatus(RunStatus.COMPLETED);
		run.setEndedAt(Instant.now());
		auditLog.record(run, null, "RUN_COMPLETED", "Orchestration completed under governance",
				Map.of("latencyMs", run.latencyMs(), "retries", run.retries(), "rollbacks", run.rollbacks()));
		metricsRegistry.onComplete(run.latencyMs());
	}

	private void fail(OrchestrationRun run, String message) {
		run.setStatus(RunStatus.FAILED);
		run.setFailureMessage(message);
		run.setEndedAt(Instant.now());
		auditLog.record(run, null, "RUN_FAILED", message, Map.of());
		metricsRegistry.onFail(run.latencyMs(), run.latencyMs());
	}

	private void safeStop(OrchestrationRun run, String reason) {
		run.setStatus(RunStatus.SAFE_STOPPED);
		run.setFailureMessage(reason);
		run.setEndedAt(Instant.now());
		auditLog.record(run, null, "SAFE_STOP", reason,
				Map.of("latencyMs", run.latencyMs(), "retries", run.retries(), "rollbacks", run.rollbacks()));
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

	private static String fingerprint(String value) {
		return Integer.toHexString(String.valueOf(value).hashCode());
	}
}
