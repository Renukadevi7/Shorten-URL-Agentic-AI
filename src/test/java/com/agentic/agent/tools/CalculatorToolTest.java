package com.agentic.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.agentic.agent.ToolResult;

class CalculatorToolTest {

	private final CalculatorTool tool = new CalculatorTool();

	@Test
	void multiplies() {
		ToolResult result = tool.run("48 * 12.5");
		assertTrue(result.ok());
		assertEquals("600", result.output());
	}

	@Test
	void evaluatesExpression() {
		assertEquals(42.0, CalculatorTool.evaluate("6 * 7"));
	}
}
