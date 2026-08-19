package com.agentic.orchestrator.ai;

import java.io.IOException;

/** A text-in, text-out call to a language model. This is the seam agents call through. */
public interface LlmClient {

    String complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException;
}
