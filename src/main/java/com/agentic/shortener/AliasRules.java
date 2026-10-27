package com.agentic.shortener;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Product alias policy. ChangeAgent-generated implementations can be installed at runtime
 * into {@link AliasRulesHolder} after compile/test succeed.
 */
public interface AliasRules {

	Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");
	Set<String> DEFAULT_RESERVED = Set.of("api", "admin", "h2-console", "css", "js", "actuator", "metrics");

	boolean isValid(String alias);

	static AliasRules defaults() {
		return alias -> {
			if (alias == null || !PATTERN.matcher(alias).matches()) {
				return false;
			}
			return !DEFAULT_RESERVED.contains(alias.toLowerCase(Locale.ROOT));
		};
	}
}
