package com.agentic.orchestration.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects {@link HttpModelClient} when {@code agentic.model.url} is set; otherwise the
 * deterministic {@link ScriptedModelClient} stand-in.
 */
@Configuration
public class ModelClientConfig {

	@Bean
	@Primary
	ModelClient modelClient(
			@Value("${agentic.model.url:}") String modelUrl,
			@Value("${agentic.model.api-key:}") String modelApiKey,
			@Value("${agentic.model.force-fail:false}") boolean forceFail) {
		if (forceFail) {
			// Test hook: every draft is invalid so ChangeAgent exhausts the 3-attempt repair loop.
			return (relativePath, requirement, scenario, attempt, hints) ->
					"package broken; public class X { this is not valid java ;;;";
		}
		if (modelUrl != null && !modelUrl.isBlank()) {
			return new HttpModelClient(modelUrl.trim(), modelApiKey);
		}
		return new ScriptedModelClient();
	}
}
