package com.agentic.agent;

public record ToolResult(boolean ok, String output) {
	public static ToolResult success(String output) {
		return new ToolResult(true, output);
	}

	public static ToolResult failure(String output) {
		return new ToolResult(false, output);
	}
}
