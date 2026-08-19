package com.agentic.urlshortener;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolving a link should populate lastAccessedAt so AnalyticsHandler can report it. */
class ShortUrlRecordTest {

    @Test
    void resolvingSetsLastAccessedAt() {
        InMemoryUrlStore store = new InMemoryUrlStore();
        UrlShortenerService service = new UrlShortenerService(store);
        var record = service.shorten("https://example.com/analytics-demo", null);

        service.resolve(record.code());

        Optional<ShortUrlRecord> analytics = service.analytics(record.code());
        assertTrue(analytics.isPresent());
        assertNotNull(analytics.get().lastAccessedAt());
    }
}
