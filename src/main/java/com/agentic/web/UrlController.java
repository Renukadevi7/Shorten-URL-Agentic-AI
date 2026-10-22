package com.agentic.web;

import java.net.URI;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.agentic.shortener.CreateUrlRequest;
import com.agentic.shortener.ShortUrl;
import com.agentic.shortener.UrlShortenerService;

@RestController
public class UrlController {

	private final UrlShortenerService shortener;

	public UrlController(UrlShortenerService shortener) {
		this.shortener = shortener;
	}

	@PostMapping("/api/urls")
	public Map<String, Object> create(@RequestBody CreateUrlRequest request) {
		try {
			ShortUrl created = shortener.shorten(request.url());
			return Map.of(
					"code", created.code(),
					"longUrl", created.longUrl(),
					"shortPath", "/" + created.code(),
					"createdAt", created.createdAt().toString());
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
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
		if ("api".equals(code) || "css".equals(code) || "js".equals(code)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		ShortUrl url = shortener.resolve(code)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown code"));
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url.longUrl())).build();
	}
}
