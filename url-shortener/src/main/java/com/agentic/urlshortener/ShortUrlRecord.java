package com.agentic.urlshortener;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class ShortUrlRecord {

    private final String code;
    private final String longUrl;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final boolean customAlias;
    private final AtomicLong clickCount = new AtomicLong();
    private volatile Instant lastAccessedAt;

    public ShortUrlRecord(String code, String longUrl, Instant createdAt, Instant expiresAt, boolean customAlias) {
        this.code = code;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.customAlias = customAlias;
    }

    public String code() {
        return code;
    }

    public String longUrl() {
        return longUrl;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean customAlias() {
        return customAlias;
    }

    public long clickCount() {
        return clickCount.get();
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public void recordClick() {
        clickCount.incrementAndGet();
        lastAccessedAt = Instant.now();
    }
}
