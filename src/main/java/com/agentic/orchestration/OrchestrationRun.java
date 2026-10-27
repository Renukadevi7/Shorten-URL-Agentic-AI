package com.agentic.orchestration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class OrchestrationRun {

	private final String id;
	private final String scenario;
	private volatile String requirement;
	private final Instant startedAt;
	private Instant endedAt;
	private RunStatus status = RunStatus.PENDING;
	private String awaitingStageId;
	private String normalizedProblem;
	private final Map<String, StageNode> stages = new LinkedHashMap<>();
	private final List<String> stageOrder = new ArrayList<>();
	private final List<AuditEvent> lineage = new CopyOnWriteArrayList<>();
	private final Map<String, Object> context = new ConcurrentHashMap<>();
	private int retries;
	private int rollbacks;
	private String failureMessage;

	public OrchestrationRun(String id, String scenario, String requirement) {
		this.id = id;
		this.scenario = scenario;
		this.requirement = requirement;
		this.startedAt = Instant.now();
	}

	public String id() {
		return id;
	}

	public String scenario() {
		return scenario;
	}

	public String requirement() {
		return requirement;
	}

	public void setRequirement(String requirement) {
		this.requirement = requirement;
	}

	public Instant startedAt() {
		return startedAt;
	}

	public Instant endedAt() {
		return endedAt;
	}

	public void setEndedAt(Instant endedAt) {
		this.endedAt = endedAt;
	}

	public RunStatus status() {
		return status;
	}

	public void setStatus(RunStatus status) {
		this.status = status;
	}

	public String awaitingStageId() {
		return awaitingStageId;
	}

	public void setAwaitingStageId(String awaitingStageId) {
		this.awaitingStageId = awaitingStageId;
	}

	public String normalizedProblem() {
		return normalizedProblem;
	}

	public void setNormalizedProblem(String normalizedProblem) {
		this.normalizedProblem = normalizedProblem;
	}

	public Map<String, StageNode> stages() {
		return stages;
	}

	public List<String> stageOrder() {
		return stageOrder;
	}

	public List<AuditEvent> lineage() {
		return lineage;
	}

	public Map<String, Object> context() {
		return context;
	}

	public int retries() {
		return retries;
	}

	public void incrementRetries() {
		retries++;
	}

	public int rollbacks() {
		return rollbacks;
	}

	public void incrementRollbacks() {
		rollbacks++;
	}

	public String failureMessage() {
		return failureMessage;
	}

	public void setFailureMessage(String failureMessage) {
		this.failureMessage = failureMessage;
	}

	public long latencyMs() {
		Instant end = endedAt == null ? Instant.now() : endedAt;
		return Duration.between(startedAt, end).toMillis();
	}

	public void addStage(StageNode node) {
		stages.put(node.id(), node);
		stageOrder.add(node.id());
	}

	public void audit(String stageId, String kind, String message, Map<String, Object> details) {
		lineage.add(new AuditEvent(Instant.now(), id, stageId, kind, message, details == null ? Map.of() : details));
	}

	public Map<String, Object> toView() {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", id);
		view.put("scenario", scenario);
		view.put("requirement", requirement);
		view.put("normalizedProblem", normalizedProblem);
		view.put("status", status.name());
		view.put("awaitingStageId", awaitingStageId);
		view.put("startedAt", startedAt.toString());
		view.put("endedAt", endedAt == null ? null : endedAt.toString());
		view.put("latencyMs", latencyMs());
		view.put("retries", retries);
		view.put("rollbacks", rollbacks);
		view.put("failureMessage", failureMessage);
		view.put("context", new LinkedHashMap<>(context));
		List<Map<String, Object>> stageViews = new ArrayList<>();
		for (String stageId : stageOrder) {
			stageViews.add(stages.get(stageId).snapshot());
		}
		view.put("stages", stageViews);
		view.put("lineage", lineage);
		return view;
	}
}
