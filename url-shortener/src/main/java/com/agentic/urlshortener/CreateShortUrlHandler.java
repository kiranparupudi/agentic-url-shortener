package com.agentic.urlshortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class CreateShortUrlHandler implements HttpHandler {

    private final UrlShortenerService service;
    private final RateLimiter rateLimiter;
    private final String publicBaseUrl;

    public CreateShortUrlHandler(UrlShortenerService service, RateLimiter rateLimiter, String publicBaseUrl) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, JsonUtil.object("error", "method not allowed"));
            return;
        }

        String clientKey = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        if (!rateLimiter.allow(clientKey)) {
            respond(exchange, 429, JsonUtil.object("error", "rate limit exceeded, try again shortly"));
            return;
        }

        try {
            Map<String, String> req = JsonUtil.parseFlatObject(readBody(exchange));
            String longUrl = req.get("longUrl");
            Long ttlSeconds = req.containsKey("ttlSeconds") && !req.get("ttlSeconds").isBlank()
                    ? Long.parseLong(req.get("ttlSeconds")) : null;

            ShortUrlRecord record = service.shorten(longUrl, ttlSeconds);

            respond(exchange, 201, JsonUtil.object(
                    "code", record.code(),
                    "shortUrl", publicBaseUrl + "/" + record.code(),
                    "longUrl", record.longUrl(),
                    "expiresAt", record.expiresAt() == null ? null : record.expiresAt().toString()));
        } catch (ValidationException e) {
            respond(exchange, 400, JsonUtil.object("error", e.getMessage()));
        } catch (NumberFormatException e) {
            respond(exchange, 400, JsonUtil.object("error", "ttlSeconds must be numeric"));
        } catch (Exception e) {
            respond(exchange, 500, JsonUtil.object("error", "internal error"));
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
