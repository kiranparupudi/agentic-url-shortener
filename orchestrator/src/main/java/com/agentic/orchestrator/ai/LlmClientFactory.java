package com.agentic.orchestrator.ai;

import java.util.Optional;

/** Builds a {@link ClaudeClient} from environment variables, or nothing if no key is set. */
public final class LlmClientFactory {

    private static final String DEFAULT_MODEL = "claude-haiku-4-5-20251001";

    private LlmClientFactory() {
    }

    public static Optional<LlmClient> fromEnvironment() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", DEFAULT_MODEL);
        return Optional.of(new ClaudeClient(apiKey, model));
    }
}
