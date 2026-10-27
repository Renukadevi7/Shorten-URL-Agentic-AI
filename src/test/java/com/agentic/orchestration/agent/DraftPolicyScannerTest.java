package com.agentic.orchestration.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DraftPolicyScannerTest {

	@Test
	void allowsCleanGeneratedAliasRules() {
		String source = """
				package com.agentic.generated;
				import com.agentic.shortener.AliasRules;
				import java.util.Locale;
				public final class GeneratedAliasRules implements AliasRules {
				  public boolean isValid(String alias) {
				    if (alias == null || !AliasRules.PATTERN.matcher(alias).matches()) return false;
				    return !AliasRules.DEFAULT_RESERVED.contains(alias.toLowerCase(Locale.ROOT));
				  }
				}
				""";
		assertTrue(DraftPolicyScanner.scan("src/main/java/com/agentic/generated/GeneratedAliasRules.java", source)
				.isEmpty());
	}

	@Test
	void blocksProcessFileNetworkReflectionAndProtectedPaths() {
		assertFalse(DraftPolicyScanner.scan("x.java", "Runtime.getRuntime().exec(\"x\")").isEmpty());
		assertFalse(DraftPolicyScanner.scan("x.java", "new ProcessBuilder(\"sh\")").isEmpty());
		assertFalse(DraftPolicyScanner.scan("x.java", "Files.writeString(Path.of(\"a\"), \"b\")").isEmpty());
		assertFalse(DraftPolicyScanner.scan("x.java", "new java.net.Socket(\"h\", 80)").isEmpty());
		assertFalse(DraftPolicyScanner.scan("x.java", "Class.forName(\"java.lang.Runtime\")").isEmpty());
		assertTrue(DraftPolicyScanner.isProtectedPath("src/main/java/com/agentic/web/ApiKeyAuthFilter.java"));
		assertFalse(DraftPolicyScanner
				.scan("src/main/java/com/agentic/web/ApiKeyAuthFilter.java", "class ApiKeyAuthFilter {}")
				.isEmpty());
	}
}
