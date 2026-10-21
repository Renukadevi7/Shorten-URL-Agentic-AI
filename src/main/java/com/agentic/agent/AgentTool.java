package com.agentic.agent;

public interface AgentTool {
	String name();

	String description();

	ToolResult run(String input);
}
