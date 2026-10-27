package com.agentic.orchestration.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Real HTTP model client. Enabled when {@code agentic.model.url} is set (see {@link ModelClientConfig}).
 * Expects a JSON response with a {@code source} (or {@code content}) field containing Java source.
 * Offline demos keep using {@link ScriptedModelClient}.
 */
public class HttpModelClient implements ModelClient {

	private final String endpoint;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper mapper = new ObjectMapper();

	public HttpModelClient(String endpoint) {
		this.endpoint = endpoint;
	}

	@Override
	public String draftSource(String relativePath, String requirement, String scenario, int attempt,
			Map<String, Object> hints) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("relativePath", relativePath);
			body.put("requirement", requirement);
			body.put("scenario", scenario);
			body.put("attempt", attempt);
			body.put("hints", hints == null ? Map.of() : hints);
			String json = mapper.writeValueAsString(body);
			HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
					.timeout(Duration.ofSeconds(60))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("Model HTTP " + response.statusCode() + ": " + response.body());
			}
			JsonNode root = mapper.readTree(response.body());
			JsonNode source = root.get("source");
			if (source == null || source.isNull()) {
				source = root.get("content");
			}
			if (source == null || source.asText().isBlank()) {
				throw new IllegalStateException("Model response missing source/content");
			}
			return source.asText();
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("HttpModelClient failed calling " + endpoint + ": " + ex.getMessage(), ex);
		}
	}
}
