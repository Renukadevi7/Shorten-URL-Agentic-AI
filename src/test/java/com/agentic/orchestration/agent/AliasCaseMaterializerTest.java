package com.agentic.orchestration.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasCaseMaterializerTest {

	@TempDir
	Path temp;

	@Test
	void derivesCasesFromRequirementAndWritesTestFile() throws Exception {
		var cases = AliasCaseMaterializer.fromRequirement(
				"Build HTTPS shortener with custom alias and security reserved admin checks");
		assertTrue(cases.stream().anyMatch(c -> "ab".equals(c.alias()) && !c.expectValid()));
		assertTrue(cases.stream().anyMatch(c -> "admin".equals(c.alias()) && !c.expectValid()));
		assertTrue(cases.stream().anyMatch(c -> "demoalias".equals(c.alias()) && c.expectValid()));

		Path file = AliasCaseMaterializer.writeTestFile(temp, "Add analytics and alias over HTTPS");
		String text = Files.readString(file);
		assertTrue(text.contains("alias-cases") || text.contains("ok-alias"));
		assertTrue(text.contains("stats-link") || text.contains("demoalias") || text.contains("https-ok"));
		assertFalse(text.isBlank());
	}
}
