package com.agentic.urlshortener;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class UrlShortenerService {

    private static final int MAX_LONG_URL_LENGTH = 2048;
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");

    private final UrlStore store;
    private final AtomicLong sequence = new AtomicLong(100_000);

    public UrlShortenerService(UrlStore store) {
        this.store = store;
    }

    public ShortUrlRecord shorten(String longUrl, String customAlias, Long ttlSeconds) {
        validateLongUrl(longUrl);

        String code;
        boolean isCustom = customAlias != null && !customAlias.isBlank();
        if (isCustom) {
            if (!ALIAS_PATTERN.matcher(customAlias).matches()) {
                throw new ValidationException("customAlias must be 3-32 chars of [a-zA-Z0-9_-]");
            }
            if (store.existsByCode(customAlias)) {
                throw new AliasConflictException("alias '" + customAlias + "' is already taken");
            }
            code = customAlias;
        } else {
            code = nextAvailableCode();
        }

        Instant now = Instant.now();
        Instant expiresAt = ttlSeconds != null && ttlSeconds > 0 ? now.plusSeconds(ttlSeconds) : null;
        ShortUrlRecord record = new ShortUrlRecord(code, longUrl, now, expiresAt, isCustom);
        store.save(record);
        return record;
    }

    // fix: this used to record the click before checking expiry, so expired
    // links kept counting clicks even though callers got a 404
    public Optional<ShortUrlRecord> resolve(String code) {
        Optional<ShortUrlRecord> found = store.findByCode(code);
        if (found.isEmpty() || found.get().isExpired()) {
            return Optional.empty();
        }
        found.get().recordClick();
        return found;
    }

    public Optional<ShortUrlRecord> analytics(String code) {
        return store.findByCode(code);
    }

    private String nextAvailableCode() {
        String code;
        do {
            code = Base62.encode(sequence.incrementAndGet());
        } while (store.existsByCode(code));
        return code;
    }

    private void validateLongUrl(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw new ValidationException("longUrl is required");
        }
        if (longUrl.length() > MAX_LONG_URL_LENGTH) {
            throw new ValidationException("longUrl exceeds max length of " + MAX_LONG_URL_LENGTH);
        }
        try {
            URI uri = new URI(longUrl);
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                throw new ValidationException("longUrl must be an absolute http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new ValidationException("longUrl is not a valid URI: " + e.getMessage());
        }
    }
}
