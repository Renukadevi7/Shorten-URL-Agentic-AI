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

	public ApiKeyAuthFilter(@Value("${agentic.api-key}") String apiKey) {
		this.apiKey = apiKey;
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

		String provided = request.getHeader("X-API-Key");
		if (provided == null || !provided.equals(apiKey)) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getOutputStream().write(
					"{\"error\":\"Missing or invalid X-API-Key\"}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isPublic(String path, String method) {
		if ("OPTIONS".equalsIgnoreCase(method)) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && ("/".equals(path) || path.startsWith("/css/")
				|| path.startsWith("/js/") || path.startsWith("/h2-console"))) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && "/api/orchestration/metrics".equals(path)) {
			return true;
		}
		// Public redirect GET /{code} — not under /api
		return "GET".equalsIgnoreCase(method) && !path.startsWith("/api/") && path.length() > 1
				&& !path.startsWith("/css") && !path.startsWith("/js") && !path.startsWith("/h2");
	}
}
