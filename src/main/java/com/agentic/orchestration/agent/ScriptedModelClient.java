package com.agentic.orchestration.agent;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for an LLM. Attempt 1 returns a broken draft so the repair loop is exercised;
 * attempt 2+ returns valid source derived from the requirement. Replace this bean with an HTTP LLM client
 * without changing ChangeAgent or the orchestrator.
 */
@Component
public class ScriptedModelClient implements ModelClient {

	@Override
	public String draftSource(String relativePath, String requirement, String scenario, int attempt,
			Map<String, Object> hints) {
		if (attempt <= 1) {
			return """
					package com.agentic.generated;
					import com.agentic.shortener.AliasRules;
					public final class GeneratedAliasRules implements AliasRules {
					  public boolean isValid(String alias) {
					    if (alias == null) return false
					    return AliasRules.PATTERN.matcher(alias).matches();
					  }
					}
					""";
		}
		return """
				package com.agentic.generated;
				import com.agentic.shortener.AliasRules;
				import java.util.Locale;
				public final class GeneratedAliasRules implements AliasRules {
				  public boolean isValid(String alias) {
				    if (alias == null || !AliasRules.PATTERN.matcher(alias).matches()) {
				      return false;
				    }
				    if (AliasRules.DEFAULT_RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
				      return false;
				    }
				    return true;
				  }
				}
				""";
	}
}
