package com.agentic.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

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
			unauthorized(response);
			return;
		}

		String provided = request.getHeader("X-API-Key");
		if (!constantTimeEquals(provided, apiKey)) {
			unauthorized(response);
			return;
		}
		filterChain.doFilter(request, response);
	}

	static boolean constantTimeEquals(String provided, String expected) {
		byte[] a = expected.getBytes(StandardCharsets.UTF_8);
		byte[] b = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
		int len = Math.max(a.length, b.length);
		byte[] aa = Arrays.copyOf(a, len);
		byte[] bb = Arrays.copyOf(b, len);
		return MessageDigest.isEqual(aa, bb) && a.length == b.length;
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getOutputStream().write("{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8));
	}

	private boolean isPublic(String path, String method) {
		if ("OPTIONS".equalsIgnoreCase(method)) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && ("/".equals(path) || path.startsWith("/css/")
				|| path.startsWith("/js/"))) {
			return true;
		}
		// Short-link redirects only (not /api/*). Metrics and config require the API key.
		return "GET".equalsIgnoreCase(method) && !path.startsWith("/api/") && path.length() > 1
				&& !path.startsWith("/css") && !path.startsWith("/js");
	}
}
