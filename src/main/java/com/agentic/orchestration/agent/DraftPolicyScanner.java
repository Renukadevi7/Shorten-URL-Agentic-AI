package com.agentic.orchestration.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static policy scan for model drafts. Blocks process control, file I/O, network,
 * reflection/unsafe APIs, and edits that target protected product paths.
 */
public final class DraftPolicyScanner {

	private static final Set<String> PROTECTED_PATH_FRAGMENTS = Set.of(
			"apikeyauthfilter",
			"apikeystartupvalidator",
			"filterconfig",
			"application.properties",
			"application-local.properties",
			"pom.xml",
			"orchestratorservice",
			"stageexecutor",
			"ratelimitfilter");

	private static final List<Pattern> BLOCKED = List.of(
			Pattern.compile("Runtime\\.getRuntime"),
			Pattern.compile("ProcessBuilder"),
			Pattern.compile("Process\\.start"),
			Pattern.compile("java\\.lang\\.Process"),
			Pattern.compile("System\\.exit"),
			Pattern.compile("System\\.loadLibrary"),
			Pattern.compile("System\\.load\\s*\\("),
			// File I/O
			Pattern.compile("java\\.io\\.File(?!NotFoundException|Permission|System)"),
			Pattern.compile("FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile"),
			Pattern.compile("Files\\.(write|writeString|newOutputStream|newBufferedWriter|copy|move|delete|createFile)"),
			Pattern.compile("java\\.nio\\.file\\.(Files|Paths|Path)"),
			Pattern.compile("FileChannel|FileLock"),
			// Network
			Pattern.compile("java\\.net\\.(Socket|ServerSocket|DatagramSocket|URLConnection|HttpURLConnection)"),
			Pattern.compile("java\\.net\\.http\\.HttpClient"),
			Pattern.compile("URL\\s*\\(\\s*\""),
			Pattern.compile("openConnection\\s*\\("),
			// Reflection / bypass
			Pattern.compile("Class\\.forName"),
			Pattern.compile("getDeclared(Method|Field|Constructor)"),
			Pattern.compile("setAccessible\\s*\\(\\s*true"),
			Pattern.compile("MethodHandles|Unsafe|VarHandle"),
			Pattern.compile("Proxy\\.newProxyInstance"),
			Pattern.compile("javax\\.script|ScriptEngine"));

	private DraftPolicyScanner() {
	}

	public static List<String> scan(String relativePath, String source) {
		List<String> hits = new ArrayList<>();
		if (relativePath != null && isProtectedPath(relativePath)) {
			hits.add("protected path edit blocked: " + relativePath);
		}
		if (source == null || source.isBlank()) {
			hits.add("empty draft");
			return hits;
		}
		for (Pattern p : BLOCKED) {
			if (p.matcher(source).find()) {
				hits.add("blocked API/pattern: " + p.pattern());
			}
		}
		String lower = source.toLowerCase(Locale.ROOT);
		for (String frag : PROTECTED_PATH_FRAGMENTS) {
			if (lower.contains(frag) && (lower.contains("files.write") || lower.contains("filewriter")
					|| lower.contains("new file(") || lower.contains("paths.get"))) {
				hits.add("protected path referenced with I/O: " + frag);
			}
		}
		return hits;
	}

	static boolean isProtectedPath(String relativePath) {
		String norm = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
		if (norm.contains("/generated/") || norm.startsWith("com/agentic/generated/")) {
			return false;
		}
		for (String frag : PROTECTED_PATH_FRAGMENTS) {
			if (norm.contains(frag)) {
				return true;
			}
		}
		return false;
	}
}
