package com.agentic.urlshortener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-window per-key rate limiter (e.g. keyed by client IP). */
public final class RateLimiter {

    private static final class Window {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger();
    }

    private final int maxRequests;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key, k -> {
            Window nw = new Window();
            nw.windowStart = now;
            return nw;
        });
        synchronized (w) {
            if (now - w.windowStart >= windowMillis) {
                w.windowStart = now;
                w.count.set(0);
            }
            return w.count.incrementAndGet() <= maxRequests;
        }
    }
}
