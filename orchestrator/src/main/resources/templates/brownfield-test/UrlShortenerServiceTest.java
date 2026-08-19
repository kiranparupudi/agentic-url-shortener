package com.agentic.urlshortener;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlShortenerServiceTest {

    @Test
    void shortenAndResolveRoundTrips() {
        UrlShortenerService service = new UrlShortenerService(new InMemoryUrlStore());
        ShortUrlRecord record = service.shorten("https://example.com/some/long/path", null, null);

        Optional<ShortUrlRecord> resolved = service.resolve(record.code());

        assertTrue(resolved.isPresent());
        assertEquals("https://example.com/some/long/path", resolved.get().longUrl());
        assertEquals(1, resolved.get().clickCount());
    }

    @Test
    void rejectsInvalidUrl() {
        UrlShortenerService service = new UrlShortenerService(new InMemoryUrlStore());
        assertThrows(ValidationException.class, () -> service.shorten("not-a-url", null, null));
    }

    @Test
    void expiredLinkIsNotResolved() {
        InMemoryUrlStore store = new InMemoryUrlStore();
        UrlShortenerService service = new UrlShortenerService(store);
        store.save(new ShortUrlRecord("exp1", "https://example.com",
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), false));

        assertTrue(service.resolve("exp1").isEmpty());
    }

    // fails against the pre-fix code - expired links shouldn't count as clicks
    @Test
    void expiredLinkClickIsNotCounted() {
        InMemoryUrlStore store = new InMemoryUrlStore();
        UrlShortenerService service = new UrlShortenerService(store);
        store.save(new ShortUrlRecord("exp2", "https://example.com",
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), false));

        service.resolve("exp2");

        assertEquals(0, store.findByCode("exp2").get().clickCount());
    }

    @Test
    void generatesUniqueCodesAcrossManyRequests() {
        UrlShortenerService service = new UrlShortenerService(new InMemoryUrlStore());
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(service.shorten("https://example.com/" + i, null, null).code());
        }
        assertEquals(200, codes.size());
    }

    @Test
    void customAliasIsHonoredAndRejectsDuplicates() {
        UrlShortenerService service = new UrlShortenerService(new InMemoryUrlStore());
        ShortUrlRecord record = service.shorten("https://example.com/promo", "summer-sale", null);

        assertEquals("summer-sale", record.code());
        assertThrows(AliasConflictException.class,
                () -> service.shorten("https://example.com/other", "summer-sale", null));
    }

    @Test
    void rejectsMalformedAlias() {
        UrlShortenerService service = new UrlShortenerService(new InMemoryUrlStore());
        assertThrows(ValidationException.class,
                () -> service.shorten("https://example.com", "a b!", null));
    }
}
