package com.agentic.urlshortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class RedirectHandler implements HttpHandler {

    private final UrlShortenerService service;

    public RedirectHandler(UrlShortenerService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String code = path.startsWith("/") ? path.substring(1) : path;

        Optional<ShortUrlRecord> record = service.resolve(code);
        if (record.isEmpty()) {
            byte[] bytes = JsonUtil.object("error", "short URL not found or expired").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        exchange.getResponseHeaders().add("Location", record.get().longUrl());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
