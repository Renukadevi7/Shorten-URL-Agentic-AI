package com.agentic.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
		"agentic.api-key=local-test-key-16c",
		"agentic.rate-limit.requests-per-minute=5"
})
class ApiIntegrationTest {

	private static final String KEY = "local-test-key-16c";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ApiKeyAuthFilter apiKeyAuthFilter;

	@Autowired
	private RateLimitFilter rateLimitFilter;

	@Autowired
	private ObjectMapper objectMapper;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		rateLimitFilter.resetWindows();
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(apiKeyAuthFilter, rateLimitFilter)
				.build();
	}

	@Test
	void createRequiresApiKeyAndSupportsAliasDelete() throws Exception {
		mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://example.com\",\"customAlias\":\"inttest1\",\"ttlSeconds\":120}"))
				.andExpect(status().isUnauthorized());

		String body = mockMvc
				.perform(post("/api/urls").header("X-API-Key", KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								"{\"url\":\"https://example.com\",\"customAlias\":\"inttest1\",\"ttlSeconds\":120}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("inttest1")).andReturn()
				.getResponse().getContentAsString();

		JsonNode json = objectMapper.readTree(body);
		String code = json.get("code").asText();

		mockMvc.perform(get("/" + code)).andExpect(status().isFound());

		mockMvc.perform(delete("/api/urls/" + code).header("X-API-Key", KEY))
				.andExpect(status().isOk());

		mockMvc.perform(get("/" + code)).andExpect(status().isNotFound());
	}

	@Test
	void orchestrationStartAndMetricsRequireAuth() throws Exception {
		mockMvc.perform(get("/api/orchestration/metrics")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/config")).andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/orchestration/runs").header("X-API-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
						"{\"scenario\":\"greenfield\",\"requirement\":\"Build a URL shortener with create and redirect APIs over HTTPS.\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
				.andExpect(jsonPath("$.awaitingStageId").value("impl"));

		mockMvc.perform(get("/api/orchestration/metrics").header("X-API-Key", KEY)).andExpect(status().isOk())
				.andExpect(jsonPath("$.reliability.runsStarted").exists());
		mockMvc.perform(get("/api/config").header("X-API-Key", KEY)).andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
	}

	@Test
	void rejectsWrongApiKeyWithGenericMessage() throws Exception {
		String body = mockMvc.perform(post("/api/urls").header("X-API-Key", "wrong-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://example.com\"}"))
				.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		org.junit.jupiter.api.Assertions.assertTrue(body.contains("Unauthorized"));
		org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase().contains("agentic_api_key"));
	}

	@Test
	void enforcesRateLimit() throws Exception {
		rateLimitFilter.resetWindows();
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/orchestration/runs").header("X-API-Key", KEY))
					.andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/orchestration/runs").header("X-API-Key", KEY))
				.andExpect(status().isTooManyRequests());
	}
}
