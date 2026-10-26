package com.agentic.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit dependency graph of SDLC stages with optional fallback edges.
 */
public class StageGraph {

	private final Map<String, StageNode> nodes = new LinkedHashMap<>();
	private final List<String> order = new ArrayList<>();

	public void add(StageNode node) {
		nodes.put(node.id(), node);
		order.add(node.id());
	}

	public StageNode get(String id) {
		return nodes.get(id);
	}

	public Map<String, StageNode> nodes() {
		return nodes;
	}

	public List<String> order() {
		return order;
	}

	public void applyTo(OrchestrationRun run) {
		for (String id : order) {
			run.addStage(nodes.get(id));
		}
	}

	public List<Map<String, Object>> view() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String id : order) {
			StageNode n = nodes.get(id);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", n.id());
			row.put("type", n.type().name());
			row.put("dependsOn", n.dependsOn());
			row.put("fallbackStageId", n.fallbackStageId());
			row.put("requiresHumanApproval", n.requiresHumanApproval());
			out.add(row);
		}
		return out;
	}
}
