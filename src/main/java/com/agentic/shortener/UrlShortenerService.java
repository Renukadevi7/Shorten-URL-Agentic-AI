package com.agentic.shortener;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

@Service
public class UrlShortenerService {

	private final Map<String, ShortUrl> store = new ConcurrentHashMap<>();
	private final AtomicBoolean analyticsEnabled = new AtomicBoolean(false);
	private final AtomicBoolean reliabilityEnabled = new AtomicBoolean(false);

	public ShortUrl shorten(String rawUrl) {
		String url = normalize(rawUrl);
		validate(url);
		String code = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		ShortUrl created = new ShortUrl(code, url, Instant.now(), 0, null);
		store.put(code, created);
		return created;
	}

	public Optional<ShortUrl> resolve(String code) {
		ShortUrl current = store.get(code);
		if (current == null) {
			return Optional.empty();
		}
		if (analyticsEnabled.get()) {
			ShortUrl updated = current.withClick();
			store.put(code, updated);
			return Optional.of(updated);
		}
		return Optional.of(current);
	}

	public Optional<ShortUrl> stats(String code) {
		if (!analyticsEnabled.get()) {
			throw new IllegalStateException("Analytics feature is not enabled (brownfield stage incomplete).");
		}
		return Optional.ofNullable(store.get(code));
	}

	public Map<String, Object> featureFlags() {
		return Map.of(
				"analyticsEnabled", analyticsEnabled.get(),
				"reliabilityEnabled", reliabilityEnabled.get(),
				"urlCount", store.size());
	}

	public void enableAnalytics() {
		analyticsEnabled.set(true);
	}

	public void enableReliability() {
		reliabilityEnabled.set(true);
	}

	public void resetProduct() {
		store.clear();
		analyticsEnabled.set(false);
		reliabilityEnabled.set(false);
	}

	public boolean isAnalyticsEnabled() {
		return analyticsEnabled.get();
	}

	public boolean isReliabilityEnabled() {
		return reliabilityEnabled.get();
	}

	private static String normalize(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new IllegalArgumentException("url is required");
		}
		return rawUrl.trim();
	}

	private static void validate(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Malformed URL");
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("Only http/https URLs are allowed");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("URL must include a host");
		}
	}
}
