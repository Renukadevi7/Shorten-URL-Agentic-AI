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
	ModelClient modelClient(@Value("${agentic.model.url:}") String modelUrl) {
		if (modelUrl != null && !modelUrl.isBlank()) {
			return new HttpModelClient(modelUrl.trim());
		}
		return new ScriptedModelClient();
	}
}
