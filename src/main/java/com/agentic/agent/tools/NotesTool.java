package com.agentic.agent.tools;

import org.springframework.stereotype.Component;

import com.agentic.agent.AgentTool;
import com.agentic.agent.ToolResult;

@Component
public class NotesTool implements AgentTool {

	@Override
	public String name() {
		return "notes";
	}

	@Override
	public String description() {
		return "Save a short working note the agent can recall later in the run.";
	}

	@Override
	public ToolResult run(String text) {
		sleep(150);
		String note = text == null ? "" : text;
		if (note.length() > 240) {
			note = note.substring(0, 240);
		}
		return ToolResult.success("Noted: " + note);
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
