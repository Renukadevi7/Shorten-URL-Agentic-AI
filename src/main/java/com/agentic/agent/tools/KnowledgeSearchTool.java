package com.agentic.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.agent.AgentTool;
import com.agentic.agent.ToolResult;

@Component
public class KnowledgeSearchTool implements AgentTool {

	private static final Map<String, String> KNOWLEDGE = new LinkedHashMap<>();

	static {
		KNOWLEDGE.put("agentic",
				"Agentic systems plan a goal, call tools, observe results, and loop until they can answer. Unlike a single chat reply, they keep state across steps.");
		KNOWLEDGE.put("react",
				"The ReAct pattern interleaves Reasoning and Acting: think, pick a tool, observe the result, then decide the next action.");
		KNOWLEDGE.put("tools",
				"Tools are functions the agent may call — search, calculate, fetch data. The model chooses which tool and what arguments.");
		KNOWLEDGE.put("planning",
				"Planning breaks a vague goal into ordered steps so the agent can recover when a tool fails or returns partial data.");
	}

	@Override
	public String name() {
		return "knowledge_search";
	}

	@Override
	public String description() {
		return "Search a local knowledge base about agents, tools, and planning.";
	}

	@Override
	public ToolResult run(String query) {
		sleep(350 + (long) (Math.random() * 250));
		String q = query == null ? "" : query.toLowerCase();

		for (Map.Entry<String, String> entry : KNOWLEDGE.entrySet()) {
			if (q.contains(entry.getKey())) {
				return ToolResult.success(entry.getValue());
			}
		}

		if (q.contains("agent") || q.contains("loop") || q.contains("step")) {
			return ToolResult.success(
					"An agent loop: (1) understand the goal, (2) plan steps, (3) call tools, (4) observe results, (5) synthesize an answer or continue.");
		}

		return ToolResult.success(
				"No exact match for \"" + query + "\". Related topics: agentic systems, ReAct, tools, planning.");
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
