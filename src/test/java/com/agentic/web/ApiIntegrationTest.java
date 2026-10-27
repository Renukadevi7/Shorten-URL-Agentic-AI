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

@SpringBootTest
class ApiIntegrationTest {

	private static final String KEY = "test-api-key";

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
	void orchestrationStartAndMetrics() throws Exception {
		mockMvc.perform(post("/api/orchestration/runs").header("X-API-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
						"{\"scenario\":\"greenfield\",\"requirement\":\"Build a URL shortener with create and redirect APIs over HTTPS.\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
				.andExpect(jsonPath("$.awaitingStageId").value("impl"));

		mockMvc.perform(get("/api/orchestration/metrics")).andExpect(status().isOk())
				.andExpect(jsonPath("$.reliability.runsStarted").exists());
	}

	@Test
	void rejectsWrongApiKey() throws Exception {
		mockMvc.perform(post("/api/urls").header("X-API-Key", "wrong-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://example.com\"}"))
				.andExpect(status().isUnauthorized());
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
