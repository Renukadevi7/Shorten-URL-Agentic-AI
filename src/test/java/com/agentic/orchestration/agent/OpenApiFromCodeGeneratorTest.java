package com.agentic.orchestration.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "agentic.api-key=local-test-key-16c")
class OpenApiFromCodeGeneratorTest {

	@Autowired
	private OpenApiFromCodeGenerator generator;

	@Test
	void yamlIsBuiltFromControllerMappings() {
		String yaml = generator.toYaml();
		assertTrue(yaml.contains("openapi: 3.0.3"));
		assertTrue(yaml.contains("Generated from live Spring MVC"));
		assertTrue(yaml.contains("/api/urls"));
		assertTrue(yaml.contains("/api/orchestration/runs"));
		assertTrue(generator.pathKeys().stream().anyMatch(p -> p.contains("/api/urls")));
	}
}
