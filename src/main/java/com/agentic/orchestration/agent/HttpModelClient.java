package com.agentic.orchestration.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Real HTTP model client. Enabled when {@code agentic.model.url} is set (see {@link ModelClientConfig}).
 * Non-local endpoints must be {@code https://}. Auth uses {@code AGENTIC_MODEL_API_KEY} /
 * {@code agentic.model.api-key} as a Bearer token when present.
 */
public class HttpModelClient implements ModelClient {

	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

	private final String endpoint;
	private final String apiKey;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper mapper = new ObjectMapper();

	public HttpModelClient(String endpoint, String apiKey) {
		this.endpoint = requireAllowedEndpoint(endpoint);
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	static String requireAllowedEndpoint(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("agentic.model.url is blank");
		}
		URI uri;
		try {
			uri = URI.create(raw.trim());
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("agentic.model.url is not a valid URI: " + raw);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean local = LOCAL_HOSTS.contains(host);
		if (local) {
			if (!"http".equals(scheme) && !"https".equals(scheme)) {
				throw new IllegalArgumentException("local model URL must use http or https");
			}
			return uri.toString();
		}
		if (!"https".equals(scheme)) {
			throw new IllegalArgumentException(
					"non-local agentic.model.url must use https:// (refusing " + scheme + "://)");
		}
		return uri.toString();
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
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
					.timeout(Duration.ofSeconds(60))
					.header("Content-Type", "application/json");
			if (!apiKey.isBlank()) {
				builder.header("Authorization", "Bearer " + apiKey);
			}
			HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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
		catch (IllegalStateException | IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("HttpModelClient failed calling " + endpoint + ": " + ex.getMessage(), ex);
		}
	}
}
