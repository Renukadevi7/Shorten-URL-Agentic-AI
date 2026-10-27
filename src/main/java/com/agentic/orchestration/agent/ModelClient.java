package com.agentic.orchestration.agent;

import java.util.Map;

/**
 * Pluggable model client. Production can swap in an HTTP LLM client; demos use
 * {@link ScriptedModelClient} so runs stay offline-reproducible.
 */
public interface ModelClient {

	/**
	 * Ask the model to draft source for a relative path.
	 * @param attempt 1-based draft attempt (callers may request a repair on attempt &gt; 1)
	 */
	String draftSource(String relativePath, String requirement, String scenario, int attempt, Map<String, Object> hints);
}
