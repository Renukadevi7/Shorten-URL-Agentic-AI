package com.agentic.shortener;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "short_urls")
public class ShortUrlEntity {

	@Id
	@Column(length = 64)
	private String code;

	@Column(nullable = false, length = 2048)
	private String longUrl;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant expiresAt;

	@Column(nullable = false)
	private long clicks;

	private Instant lastClickedAt;

	@Column(nullable = false)
	private boolean deleted;

	protected ShortUrlEntity() {
	}

	public ShortUrlEntity(String code, String longUrl, Instant createdAt, Instant expiresAt) {
		this.code = code;
		this.longUrl = longUrl;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.clicks = 0;
		this.deleted = false;
	}

	public String getCode() {
		return code;
	}

	public String getLongUrl() {
		return longUrl;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public long getClicks() {
		return clicks;
	}

	public Instant getLastClickedAt() {
		return lastClickedAt;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public void markDeleted() {
		this.deleted = true;
	}

	public void recordClick() {
		this.clicks++;
		this.lastClickedAt = Instant.now();
	}

	public void restoreCounters(long clicks, Instant lastClickedAt) {
		this.clicks = clicks;
		this.lastClickedAt = lastClickedAt;
	}

	public void forceExpire(Instant when) {
		this.expiresAt = when;
	}

	public boolean isExpired(Instant now) {
		return expiresAt != null && now.isAfter(expiresAt);
	}

	public ShortUrl toView() {
		return new ShortUrl(code, longUrl, createdAt, clicks, lastClickedAt, expiresAt, deleted);
	}
}
