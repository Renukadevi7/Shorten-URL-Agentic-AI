package com.agentic.orchestration.agent;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.agentic.shortener.AliasRules;

/**
 * Forked-JVM entrypoint: load generated AliasRules from a classes directory and evaluate
 * cases from a TSV test file. Exit 0 on success, non-zero on failure. Designed so a hung
 * or malicious draft cannot freeze the parent Spring Boot process.
 */
public final class AliasRulesIsolatedProbe {

	private AliasRulesIsolatedProbe() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: AliasRulesIsolatedProbe <classesDir> <casesTsv> [fqcn]");
			System.exit(2);
		}
		Path classesDir = Path.of(args[0]);
		Path casesFile = Path.of(args[1]);
		String fqcn = args.length >= 3 ? args[2] : "com.agentic.generated.GeneratedAliasRules";

		URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
				AliasRules.class.getClassLoader());
		Class<?> clazz = Class.forName(fqcn, true, loader);
		Object instance = clazz.getDeclaredConstructor().newInstance();
		if (!(instance instanceof AliasRules)) {
			System.err.println("generated class does not implement AliasRules");
			System.exit(3);
		}
		AliasRules rules = (AliasRules) instance;

		List<String> lines = Files.readAllLines(casesFile, StandardCharsets.UTF_8);
		int checked = 0;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			String[] parts = trimmed.split("\t");
			if (parts.length < 2) {
				System.err.println("bad case line: " + trimmed);
				System.exit(4);
			}
			String alias = parts[0];
			boolean expect = Boolean.parseBoolean(parts[1]);
			boolean actual = rules.isValid(alias);
			if (actual != expect) {
				System.err.println("case failed alias=" + alias + " expect=" + expect + " actual=" + actual
						+ (parts.length > 2 ? " reason=" + parts[2] : ""));
				System.exit(1);
			}
			checked++;
		}
		if (checked == 0) {
			System.err.println("no cases in " + casesFile);
			System.exit(5);
		}
		System.out.println("ok cases=" + checked);
		System.exit(0);
	}
}
