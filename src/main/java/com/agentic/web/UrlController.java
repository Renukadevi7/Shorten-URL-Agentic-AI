package com.agentic.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.agentic.orchestration.PolicyGuardrail;
import com.agentic.shortener.CreateUrlRequest;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

@RestController
public class UrlController {

	private final UrlShortenerService shortener;
	private final PolicyGuardrail policyGuardrail;

	public UrlController(UrlShortenerService shortener, PolicyGuardrail policyGuardrail) {
		this.shortener = shortener;
		this.policyGuardrail = policyGuardrail;
	}

	@PostMapping("/api/urls")
	public Map<String, Object> create(@RequestBody CreateUrlRequest request) {
		try {
			List<String> violations = policyGuardrail.validateUrlMutation(request.url(), request.customAlias());
			if (!violations.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", violations));
			}
			ShortUrl created = shortener.shorten(request.url(), request.customAlias(), request.ttlSeconds());
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("code", created.code());
			body.put("longUrl", created.longUrl());
			body.put("shortPath", "/" + created.code());
			body.put("createdAt", created.createdAt().toString());
			body.put("expiresAt", created.expiresAt() == null ? null : created.expiresAt().toString());
			return body;
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

	@DeleteMapping("/api/urls/{code}")
	public Map<String, Object> delete(@PathVariable String code) {
		boolean deleted = shortener.delete(code);
		if (!deleted) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or already deleted code");
		}
		return Map.of("code", code, "deleted", true);
	}

	@GetMapping("/api/urls/{code}/stats")
	public ShortUrl stats(@PathVariable String code) {
		try {
			return shortener.stats(code)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown code"));
		}
		catch (IllegalStateException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
		}
	}

	@GetMapping("/api/urls/features")
	public Map<String, Object> features() {
		return shortener.featureFlags();
	}

	@GetMapping("/{code}")
	public ResponseEntity<Void> redirect(@PathVariable String code) {
		if ("api".equals(code) || "css".equals(code) || "js".equals(code) || "h2-console".equals(code)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		ShortUrl url = shortener.resolve(code)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Unknown, deleted, or expired code"));
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url.longUrl())).build();
	}
}
