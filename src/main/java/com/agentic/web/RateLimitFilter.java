package com.agentic.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RateLimitFilter extends OncePerRequestFilter {

	private final int limit;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	public RateLimitFilter(@Value("${agentic.rate-limit.requests-per-minute:60}") int limit) {
		this.limit = limit;
	}

	/** Test helper: reset in-memory windows between cases. */
	public void resetWindows() {
		windows.clear();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (!request.getRequestURI().startsWith("/api/")) {
			filterChain.doFilter(request, response);
			return;
		}
		String key = request.getHeader("X-API-Key");
		if (key == null || key.isBlank()) {
			key = request.getRemoteAddr();
		}
		Window window = windows.computeIfAbsent(key, k -> new Window());
		if (!window.allow(limit)) {
			response.setStatus(429);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getOutputStream().write(
					"{\"error\":\"Rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		filterChain.doFilter(request, response);
	}

	private static final class Window {
		private long epochMinute = Instant.now().getEpochSecond() / 60;
		private final AtomicInteger count = new AtomicInteger();

		synchronized boolean allow(int limit) {
			long now = Instant.now().getEpochSecond() / 60;
			if (now != epochMinute) {
				epochMinute = now;
				count.set(0);
			}
			return count.incrementAndGet() <= limit;
		}
	}
}
