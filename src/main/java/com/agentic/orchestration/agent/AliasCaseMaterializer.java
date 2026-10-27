package com.agentic.orchestration.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.agentic.shortener.AliasRules;

/**
 * Builds AliasRules unit-check cases from the run requirement (and reserved defaults),
 * then writes them as a TSV test file for the isolated JVM probe.
 */
public final class AliasCaseMaterializer {

	private AliasCaseMaterializer() {
	}

	public record Case(String alias, boolean expectValid, String reason) {
	}

	public static List<Case> fromRequirement(String requirement) {
		String req = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
		LinkedHashSet<Case> cases = new LinkedHashSet<>();

		// Pattern floor: length and charset from AliasRules.PATTERN
		cases.add(new Case("ok-alias", true, "meets pattern min length"));
		cases.add(new Case("ab", false, "below pattern min length"));
		cases.add(new Case("bad alias!", false, "illegal characters"));

		// Reserved words always enforced by product policy
		for (String reserved : AliasRules.DEFAULT_RESERVED) {
			cases.add(new Case(reserved, false, "default reserved"));
		}

		// Requirement-derived expectations
		if (req.contains("alias") || req.contains("custom")) {
			cases.add(new Case("demoalias", true, "requirement mentions custom alias"));
		}
		if (req.contains("admin") || req.contains("reserved") || req.contains("security")) {
			cases.add(new Case("admin", false, "requirement emphasizes reserved/admin"));
		}
		if (req.contains("analytics") || req.contains("stats")) {
			cases.add(new Case("stats-link", true, "analytics scenario sample alias shape"));
		}
		if (req.contains("https")) {
			cases.add(new Case("https-ok", true, "https-oriented requirement still needs valid alias"));
		}
		return List.copyOf(cases);
	}

	/** Writes {@code alias\\texpectValid\\treason} lines. */
	public static Path writeTestFile(Path workspace, String requirement) throws IOException {
		Path file = workspace.resolve("src/test/resources/alias-cases.tsv");
		Files.createDirectories(file.getParent());
		List<String> lines = new ArrayList<>();
		lines.add("# derived from requirement; consumed by AliasRulesIsolatedProbe");
		for (Case c : fromRequirement(requirement)) {
			lines.add(c.alias() + "\t" + c.expectValid() + "\t" + c.reason());
		}
		Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return file;
	}
}
