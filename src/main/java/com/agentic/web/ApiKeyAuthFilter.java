package com.agentic.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

	private final String apiKey;

	public ApiKeyAuthFilter(@Value("${agentic.api-key:}") String apiKey) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		String method = request.getMethod();

		if (isPublic(path, method)) {
			filterChain.doFilter(request, response);
			return;
		}

		if (apiKey.isBlank()) {
			unauthorized(response, "API key not configured. Set AGENTIC_API_KEY (or application-local.properties).");
			return;
		}

		String provided = request.getHeader("X-API-Key");
		if (provided == null || !provided.equals(apiKey)) {
			unauthorized(response, "Missing or invalid X-API-Key");
			return;
		}
		filterChain.doFilter(request, response);
	}

	private static void unauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		String body = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
		response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
	}

	private boolean isPublic(String path, String method) {
		if ("OPTIONS".equalsIgnoreCase(method)) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && ("/".equals(path) || path.startsWith("/css/")
				|| path.startsWith("/js/"))) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && "/api/orchestration/metrics".equals(path)) {
			return true;
		}
		return "GET".equalsIgnoreCase(method) && !path.startsWith("/api/") && path.length() > 1
				&& !path.startsWith("/css") && !path.startsWith("/js");
	}
}
