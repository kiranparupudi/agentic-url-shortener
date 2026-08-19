package com.agentic.urlshortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Adds lastAccessedAt to the response, per the clarified analytics requirement. */
public final class AnalyticsHandler implements HttpHandler {

    private static final String PREFIX = "/api/analytics/";

    private final UrlShortenerService service;

    public AnalyticsHandler(UrlShortenerService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String code = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";

        Optional<ShortUrlRecord> record = service.analytics(code);
        String json;
        int status;
        if (record.isEmpty()) {
            status = 404;
            json = JsonUtil.object("error", "unknown code");
        } else {
            status = 200;
            ShortUrlRecord r = record.get();
            json = JsonUtil.object(
                    "code", r.code(),
                    "longUrl", r.longUrl(),
                    "clickCount", r.clickCount(),
                    "createdAt", r.createdAt().toString(),
                    "lastAccessedAt", r.lastAccessedAt() == null ? null : r.lastAccessedAt().toString(),
                    "expiresAt", r.expiresAt() == null ? null : r.expiresAt().toString());
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
