package com.agentic.shortener;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

@Service
public class UrlShortenerService {

	private final ShortUrlRepository repository;
	private final EntityManager entityManager;
	private final AliasRulesHolder aliasRulesHolder;
	private final AtomicBoolean analyticsEnabled = new AtomicBoolean(true);
	private final AtomicBoolean reliabilityEnabled = new AtomicBoolean(true);

	public UrlShortenerService(ShortUrlRepository repository, EntityManager entityManager,
			AliasRulesHolder aliasRulesHolder) {
		this.repository = repository;
		this.entityManager = entityManager;
		this.aliasRulesHolder = aliasRulesHolder;
	}

	@Transactional
	public ShortUrl shorten(String rawUrl, String customAlias, Long ttlSeconds) {
		String url = normalize(rawUrl);
		validate(url);
		String code = allocateCode(customAlias);
		Instant expiresAt = null;
		if (ttlSeconds != null) {
			if (ttlSeconds <= 0) {
				throw new IllegalArgumentException("ttlSeconds must be positive");
			}
			expiresAt = Instant.now().plusSeconds(ttlSeconds);
		}
		ShortUrlEntity entity = new ShortUrlEntity(code, url, Instant.now(), expiresAt);
		try {
			// persist() rejects duplicates; save() would merge and overwrite under races.
			entityManager.persist(entity);
			entityManager.flush();
		}
		catch (DataIntegrityViolationException | PersistenceException ex) {
			throw new IllegalArgumentException("customAlias already in use");
		}
		return entity.toView();
	}

	@Transactional
	public Optional<ShortUrl> resolve(String code) {
		Optional<ShortUrlEntity> found = repository.findById(code);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		ShortUrlEntity entity = found.get();
		if (entity.isDeleted() || entity.isExpired(Instant.now())) {
			return Optional.empty();
		}
		if (analyticsEnabled.get()) {
			// Atomic increment avoids lost updates under concurrent redirects.
			repository.incrementClicksIfActive(code, Instant.now());
			return repository.findById(code).map(ShortUrlEntity::toView);
		}
		return Optional.of(entity.toView());
	}

	@Transactional(readOnly = true)
	public Optional<ShortUrl> stats(String code) {
		if (!analyticsEnabled.get()) {
			throw new IllegalStateException("Analytics feature is disabled");
		}
		return repository.findById(code).filter(e -> !e.isDeleted()).map(ShortUrlEntity::toView);
	}

	@Transactional
	public boolean delete(String code) {
		Optional<ShortUrlEntity> found = repository.findById(code);
		if (found.isEmpty() || found.get().isDeleted()) {
			return false;
		}
		ShortUrlEntity entity = found.get();
		entity.markDeleted();
		repository.save(entity);
		return true;
	}

	@Transactional
	public boolean forceExpire(String code) {
		Optional<ShortUrlEntity> found = repository.findById(code);
		if (found.isEmpty()) {
			return false;
		}
		ShortUrlEntity entity = found.get();
		entity.forceExpire(Instant.now().minusSeconds(1));
		repository.save(entity);
		return true;
	}

	public Map<String, Object> featureFlags() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("analyticsEnabled", analyticsEnabled.get());
		map.put("reliabilityEnabled", reliabilityEnabled.get());
		map.put("urlCount", repository.count());
		map.put("persistentStorage", "H2");
		return map;
	}

	public void enableAnalytics() {
		analyticsEnabled.set(true);
	}

	public void enableReliability() {
		reliabilityEnabled.set(true);
	}

	public void disableAnalytics() {
		analyticsEnabled.set(false);
	}

	public void disableReliability() {
		reliabilityEnabled.set(false);
	}

	@Transactional
	public void resetProduct() {
		repository.deleteAll();
		analyticsEnabled.set(true);
		reliabilityEnabled.set(true);
	}

	public boolean isAnalyticsEnabled() {
		return analyticsEnabled.get();
	}

	public boolean isReliabilityEnabled() {
		return reliabilityEnabled.get();
	}

	@Transactional(readOnly = true)
	public ProductSnapshot snapshot() {
		List<Map<String, Object>> urls = new ArrayList<>();
		for (ShortUrlEntity entity : repository.findAll()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("code", entity.getCode());
			row.put("longUrl", entity.getLongUrl());
			row.put("createdAt", entity.getCreatedAt().toString());
			row.put("expiresAt", entity.getExpiresAt() == null ? null : entity.getExpiresAt().toString());
			row.put("clicks", entity.getClicks());
			row.put("deleted", entity.isDeleted());
			urls.add(row);
		}
		return new ProductSnapshot(analyticsEnabled.get(), reliabilityEnabled.get(), urls);
	}

	@Transactional
	public void restore(ProductSnapshot snapshot) {
		repository.deleteAll();
		analyticsEnabled.set(snapshot.analyticsEnabled());
		reliabilityEnabled.set(snapshot.reliabilityEnabled());
		for (Map<String, Object> row : snapshot.urls()) {
			String code = String.valueOf(row.get("code"));
			String longUrl = String.valueOf(row.get("longUrl"));
			Instant createdAt = Instant.parse(String.valueOf(row.get("createdAt")));
			Instant expiresAt = row.get("expiresAt") == null ? null
					: Instant.parse(String.valueOf(row.get("expiresAt")));
			ShortUrlEntity entity = new ShortUrlEntity(code, longUrl, createdAt, expiresAt);
			if (Boolean.TRUE.equals(row.get("deleted"))) {
				entity.markDeleted();
			}
			long clicks = ((Number) row.getOrDefault("clicks", 0)).longValue();
			entity.restoreCounters(clicks, null);
			repository.save(entity);
		}
	}

	private String allocateCode(String customAlias) {
		if (customAlias == null || customAlias.isBlank()) {
			return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		}
		String alias = customAlias.trim();
		if (!aliasRulesHolder.get().isValid(alias)) {
			throw new IllegalArgumentException("customAlias rejected by AliasRules policy");
		}
		// Do not check-then-act; rely on primary-key uniqueness + PersistenceException.
		return alias;
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
		String host = uri.getHost().toLowerCase();
		if (host.equals("localhost") || host.endsWith(".local") || host.startsWith("127.") || host.equals("0.0.0.0")) {
			throw new IllegalArgumentException("Local/loopback targets are blocked by security policy");
		}
	}
}
