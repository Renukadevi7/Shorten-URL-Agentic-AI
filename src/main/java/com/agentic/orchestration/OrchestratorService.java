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
		return start(scenario, requirement, null, true);
	}

	public OrchestrationRun start(String scenario, String requirement, String faultType, boolean faultOnce) {
		List<String> violations = policyGuardrail.validateRequirement(requirement);
		if (!violations.isEmpty()) {
			throw new IllegalArgumentException("Policy guardrail blocked start: " + String.join("; ", violations));
		}

		OrchestrationRun run = new OrchestrationRun(UUID.randomUUID().toString().substring(0, 8), scenario, requirement);
		List<PolicyDecision> policyDecisions = policyGuardrail.evaluateRequirement(requirement);
		run.context().put("requirementPolicy", policyGuardrail.auditBundle(policyDecisions));
		scenarioFactory.materialize(run);
		run.context().put("requirementFingerprint", fingerprint(requirement));
		if (faultType != null && !faultType.isBlank()) {
			run.context().put("faultInjection", faultType);
			run.context().put("faultOnce", faultOnce);
			auditLog.record(run, null, "FAULT_INJECTION", "Pre-seeded fault " + faultType, Map.of("once", faultOnce));
		}
		auditLog.record(run, null, "POLICY_EVAL", "Requirement policy allow/block decisions",
				policyGuardrail.auditBundle(policyDecisions));
		auditLog.record(run, null, "RUN_STARTED", "Scenario materialized with dependency graph",
				Map.of("scenario", scenario, "graph", run.context().get("stageGraph"),
						"stageGraphSource", run.context().get("stageGraphSource")));
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

		if (stage.completesRun()) {
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
		markRecovery(run, "RESUME");
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

	public OrchestrationRun injectFault(String id, String faultType, boolean once) {
		OrchestrationRun run = require(id);
		run.context().put("faultInjection", faultType);
		run.context().put("faultOnce", once);
		auditLog.record(run, null, "FAULT_INJECTION", "Injected fault " + faultType, Map.of("once", once));
		return run;
	}

	public OrchestrationRun mutateUpstream(String id, String newRequirement) {
		OrchestrationRun run = require(id);
		String oldFp = String.valueOf(run.context().get("requirementFingerprint"));
		run.context().put("previousRequirement", run.requirement());
		run.context().put("upstreamRequirement", newRequirement);
		run.setRequirement(newRequirement);
		run.context().put("requirementFingerprint", fingerprint(newRequirement));
		run.context().put("replanReason", "Upstream requirement fingerprint changed: " + oldFp + " -> "
				+ run.context().get("requirementFingerprint"));
		auditLog.record(run, null, "UPSTREAM_CHANGED", "Upstream output/requirement changed; scheduling replan",
				Map.of("oldFingerprint", oldFp, "newFingerprint", run.context().get("requirementFingerprint"),
						"upstreamRequirement", newRequirement));
		// Upstream change invalidates in-flight approvals; force a running replan wave.
		if (run.awaitingStageId() != null) {
			StageNode paused = run.stages().get(run.awaitingStageId());
			if (paused != null && paused.status() == StageStatus.AWAITING_APPROVAL) {
				paused.setStatus(StageStatus.BLOCKED);
				paused.resetAttempts();
			}
			run.setAwaitingStageId(null);
		}
		dynamicReplan(run);
		if (!isTerminal(run.status())) {
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
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

			if (run.status() == RunStatus.AWAITING_APPROVAL || isTerminal(run.status())) {
				return;
			}

			boolean progressed = ready.stream().anyMatch(stage -> stage.status() != StageStatus.BLOCKED);
			if (!progressed) {
				safeStop(run, "No progress: ready stages remained entry-gate blocked");
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
				run.context().put("entryBlocked_" + stage.id(), true);
				return;
			}

			stage.setStatus(StageStatus.RUNNING);
			stage.incrementAttempts();
			long enteredAt = System.currentTimeMillis();
			stage.artifacts().put("enteredAtMs", enteredAt);
			auditLog.record(run, stage.id(), "STAGE_ENTER",
					"Entering stage " + stage.title() + " on " + Thread.currentThread().getName(),
					Map.of("attempt", stage.attempts(), "thread", Thread.currentThread().getName(),
							"enteredAtMs", enteredAt));
		}

		try {
			stageExecutor.execute(run, stage);
			synchronized (run) {
				stage.artifacts().put("exitedAtMs", System.currentTimeMillis());
				exitGate(run, stage);
			}
		}
		catch (RuntimeException ex) {
			handleFailure(run, stage, ex);
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
		synchronized (run) {
			markFailureStart(run);
			auditLog.record(run, stage.id(), "STAGE_FAILURE", ex.getMessage(),
					Map.of("attempt", stage.attempts()));
		}
		if (stage.attempts() <= stage.maxRetries()) {
			synchronized (run) {
				run.setStatus(RunStatus.RETRYING);
				run.incrementRetries();
				metricsRegistry.onRetry();
				auditLog.record(run, stage.id(), "RETRY", "Bounded retry after failure",
						Map.of("attempt", stage.attempts(), "maxRetries", stage.maxRetries()));
			}
			try {
				stageExecutor.execute(run, stage);
				synchronized (run) {
					exitGate(run, stage);
					markRecovery(run, "RETRY_SUCCESS");
					if (run.status() != RunStatus.AWAITING_APPROVAL) {
						run.setStatus(RunStatus.RUNNING);
					}
				}
				return;
			}
			catch (RuntimeException retryEx) {
				synchronized (run) {
					auditLog.record(run, stage.id(), "RETRY_FAILED", retryEx.getMessage(), Map.of());
				}
			}
		}

		StageNode fallback = null;
		synchronized (run) {
			if (stage.fallbackStageId() != null) {
				fallback = run.stages().get(stage.fallbackStageId());
				if (fallback != null) {
					stage.setStatus(StageStatus.FAILED);
					auditLog.record(run, stage.id(), "FALLBACK_ACTIVATED",
							"Primary stage exhausted retries; activating fallback " + fallback.id(), Map.of());
					run.context().put("fallbackFor_" + stage.id(), fallback.id());
					fallback.setStatus(StageStatus.READY);
					fallback.resetAttempts();
					run.context().remove("entryBlocked_" + fallback.id());
				}
			}
		}

		if (fallback != null) {
			try {
				executeStage(run, fallback);
				if (fallback.status() == StageStatus.SUCCEEDED) {
					synchronized (run) {
						run.context().put("acceptedFallback_" + stage.id(), true);
						stage.setStatus(StageStatus.SKIPPED);
						stage.artifacts().put("passed", true);
						stage.artifacts().put("testPassed", true);
						stage.artifacts().put("testReport", "Accepted via fallback " + fallback.id());
						stage.setOutput("Skipped after successful fallback " + fallback.id());
						// Clear sticky entry blocks for any stage that depended on the failed primary.
						run.context().keySet().removeIf(k -> k.startsWith("entryBlocked_"));
						markRecovery(run, "FALLBACK_SUCCESS");
						run.setStatus(RunStatus.RUNNING);
					}
					return;
				}
			}
			catch (RuntimeException fallbackEx) {
				synchronized (run) {
					auditLog.record(run, fallback.id(), "FALLBACK_FAILED", fallbackEx.getMessage(), Map.of());
				}
			}
		}

		synchronized (run) {
			performRollback(run, stage);
			safeStop(run, "Stage failed after retries/fallback: " + ex.getMessage());
		}
	}

	private void markFailureStart(OrchestrationRun run) {
		if (run.context().get("failureStartedAtMs") == null) {
			long at = System.currentTimeMillis();
			run.context().put("failureStartedAtMs", at);
			auditLog.record(run, null, "FAILURE_CLOCK_START", "MTTR clock started", Map.of("atMs", at));
		}
	}

	private void markRecovery(OrchestrationRun run, String reason) {
		Object started = run.context().remove("failureStartedAtMs");
		if (started instanceof Number n) {
			long mttr = Math.max(0, System.currentTimeMillis() - n.longValue());
			metricsRegistry.onRecovery(mttr);
			auditLog.record(run, null, "RECOVERY", "Recovered after failure via " + reason,
					Map.of("mttrMs", mttr, "reason", reason));
		}
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

	private void dynamicReplan(OrchestrationRun run) {
		String reason = String.valueOf(run.context().getOrDefault("replanReason", "Upstream changed"));
		String replanId = "replan_" + run.stages().size();
		String anchor = run.stageOrder().stream()
				.filter(id -> {
					StageNode n = run.stages().get(id);
					return n != null && n.status() == StageStatus.SUCCEEDED;
				})
				.reduce((a, b) -> b)
				.orElseGet(() -> run.stageOrder().isEmpty() ? "unknown" : run.stageOrder().get(0));

		StageNode replan = StageNode.builder(replanId, StageType.REPLAN, "Dynamic re-plan after upstream change")
				.dependsOn(anchor)
				.exitArtifacts("replanReason", "clarifiedRequirement")
				.build();
		run.addStage(replan);
		replan.setStatus(StageStatus.READY);

		for (String id : run.stageOrder()) {
			StageNode node = run.stages().get(id);
			if (node.status() == StageStatus.SUCCEEDED || node.id().equals(replanId)) {
				continue;
			}
			if (dependsTransitivelyOn(run, node, anchor) && !node.activationOnly()) {
				node.setStatus(StageStatus.BLOCKED);
				node.resetAttempts();
			}
		}
		run.context().put("architectureReady", false);
		run.context().put("securityReviewPassed", false);
		run.context().put("implementationDone", false);
		run.context().put("testsPassed", false);
		run.context().put("docsReady", false);
		run.context().remove("replanApplied");
		run.context().keySet().removeIf(k -> k.startsWith("entryBlocked_"));

		// Rewire first architecture-typed stage onto the replan node (metadata-driven, not id hard-code).
		run.stages().values().stream()
				.filter(n -> n.type() == StageType.ARCHITECTURE)
				.findFirst()
				.ifPresent(arch -> run.stages().put(arch.id(), arch.withDependsOn(List.of(replanId))));

		run.context().put("replanScheduled", true);
		auditLog.record(run, replanId, "DYNAMIC_REPLAN",
				"Inserted REPLAN stage and invalidated downstream after upstream change",
				Map.of("reason", reason, "anchor", anchor));
	}

	private boolean dependsTransitivelyOn(OrchestrationRun run, StageNode node, String anchor) {
		if (node.dependsOn().contains(anchor)) {
			return true;
		}
		for (String dep : node.dependsOn()) {
			StageNode parent = run.stages().get(dep);
			if (parent != null && dependsTransitivelyOn(run, parent, anchor)) {
				return true;
			}
		}
		return false;
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
			if (Boolean.TRUE.equals(run.context().get("entryBlocked_" + stage.id()))) {
				continue;
			}
			// activationOnly stages (fallbacks) wait until explicitly set READY.
			if (stage.activationOnly() && stage.status() != StageStatus.READY) {
				continue;
			}
			boolean depsMet = stage.dependsOn().stream().allMatch(dep -> {
				StageNode parent = run.stages().get(dep);
				if (parent == null) {
					return false;
				}
				if (parent.status() == StageStatus.SUCCEEDED || parent.status() == StageStatus.SKIPPED) {
					return true;
				}
				return Boolean.TRUE.equals(run.context().get("acceptedFallback_" + dep));
			});
			if (!depsMet) {
				if (!stage.activationOnly()) {
					stage.setStatus(StageStatus.BLOCKED);
				}
				continue;
			}
			if (!stage.activationOnly() || stage.status() == StageStatus.READY) {
				if (!stage.activationOnly()) {
					stage.setStatus(StageStatus.READY);
				}
				ready.add(stage);
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
		metricsRegistry.onFail(run.latencyMs());
	}

	private void safeStop(OrchestrationRun run, String reason) {
		run.setStatus(RunStatus.SAFE_STOPPED);
		run.setFailureMessage(reason);
		run.setEndedAt(Instant.now());
		auditLog.record(run, null, "SAFE_STOP", reason,
				Map.of("latencyMs", run.latencyMs(), "retries", run.retries(), "rollbacks", run.rollbacks()));
		metricsRegistry.onFail(run.latencyMs());
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
