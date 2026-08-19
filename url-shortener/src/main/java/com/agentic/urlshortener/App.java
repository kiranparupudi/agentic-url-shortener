package com.agentic.urlshortener;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class App {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String publicBaseUrl = System.getenv().getOrDefault("PUBLIC_BASE_URL", "http://localhost:" + port);

        UrlStore store = new InMemoryUrlStore();
        UrlShortenerService service = new UrlShortenerService(store);
        RateLimiter rateLimiter = new RateLimiter(20, 60_000);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/shorten", new CreateShortUrlHandler(service, rateLimiter, publicBaseUrl));
        server.createContext("/api/analytics/", new AnalyticsHandler(service));
        server.createContext("/health", new HealthHandler());
        server.createContext("/", new RedirectHandler(service));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("url-shortener listening on port " + port + " (public base url: " + publicBaseUrl + ")");
    }
}
