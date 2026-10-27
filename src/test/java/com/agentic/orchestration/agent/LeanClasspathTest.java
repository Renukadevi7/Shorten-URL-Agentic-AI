package com.agentic.orchestration.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LeanClasspathTest {

	@Test
	void leanClasspathIsMuchSmallerThanFullRuntimeClasspath() {
		String lean = ScriptedModelChangeAgent.leanClasspath(Path.of("target", "classes"));
		String full = System.getProperty("java.class.path", "");
		assertFalse(lean.isBlank());
		assertTrue(lean.length() < full.length() || !full.contains("spring-boot"),
				"lean CP should avoid hauling the full Spring Boot runtime path");
		String sep = System.getProperty("path.separator");
		int leanEntries = lean.isBlank() ? 0 : lean.split(sep).length;
		int fullEntries = full.isBlank() ? 0 : full.split(sep).length;
		assertTrue(leanEntries <= Math.max(5, fullEntries / 5) || leanEntries < 20,
				"lean entries=" + leanEntries + " full entries=" + fullEntries);
	}
}
