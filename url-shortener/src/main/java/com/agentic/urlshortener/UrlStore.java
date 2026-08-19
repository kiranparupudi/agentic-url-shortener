package com.agentic.urlshortener;

import java.util.Optional;

public interface UrlStore {
    boolean existsByCode(String code);

    void save(ShortUrlRecord record);

    Optional<ShortUrlRecord> findByCode(String code);
}
