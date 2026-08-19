package com.agentic.orchestrator.core;

import java.time.Instant;

/** One entry in the decision lineage - what an agent decided, and why. */
public record Decision(String stageId, String summary, String rationale, Instant timestamp) {

    public static Decision of(String stageId, String summary, String rationale) {
        return new Decision(stageId, summary, rationale, Instant.now());
    }

    @Override
    public String toString() {
        return "[" + stageId + "] " + summary + " -- because: " + rationale;
    }
}
