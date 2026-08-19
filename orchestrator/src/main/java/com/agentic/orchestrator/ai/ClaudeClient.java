package com.agentic.orchestrator.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Real call to the Claude Messages API. Uses only the JDK's built-in
 * HttpClient plus a couple of hand-rolled JSON helpers (see JsonUtil in the
 * url-shortener module for the same pattern) so no new dependency is needed
 * just for this.
 */
public final class ClaudeClient implements LlmClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public ClaudeClient(String apiKey, String model) {
        this(apiKey, model, 1024);
    }

    public ClaudeClient(String apiKey, String model, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        String body = "{"
                + "\"model\":\"" + escape(model) + "\","
                + "\"max_tokens\":" + maxTokens + ","
                + "\"system\":\"" + escape(systemPrompt) + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(userPrompt) + "\"}]"
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Claude API returned " + response.statusCode() + ": " + response.body());
        }

        String text = extractFirstTextField(response.body());
        if (text == null) {
            throw new IOException("could not find a text field in Claude's response: " + response.body());
        }
        return text;
    }

    /** Finds the first {@code "text":"..."} value in a JSON blob and unescapes it. */
    private static String extractFirstTextField(String json) {
        String marker = "\"text\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
                i++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
