package com.agentic.urlshortener;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryUrlStore implements UrlStore {

    private final Map<String, ShortUrlRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean existsByCode(String code) {
        return records.containsKey(code);
    }

    @Override
    public void save(ShortUrlRecord record) {
        records.put(record.code(), record);
    }

    @Override
    public Optional<ShortUrlRecord> findByCode(String code) {
        return Optional.ofNullable(records.get(code));
    }
}
