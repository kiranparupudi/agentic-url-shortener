package com.agentic.orchestrator.core;

import java.util.List;
import java.util.Optional;

/** One step in the SDLC pipeline - requirements, implementation, testing, and so on. */
public interface Stage {

    String id();

    String description();

    List<String> dependsOn();

    default boolean requiresApproval() {
        return false;
    }

    default Gate entryGate() {
        return Gate.alwaysPass();
    }

    default Gate exitGate() {
        return Gate.alwaysPass();
    }

    default RetryPolicy retryPolicy() {
        return RetryPolicy.standard();
    }

    /** Backup stage to try once this one runs out of retries. */
    default Optional<Stage> fallback() {
        return Optional.empty();
    }

    StageResult execute(ExecutionContext ctx) throws Exception;

    /** Undo this stage's own side effects. Called when it fails for good. */
    default void rollback(ExecutionContext ctx) {
        // no-op by default
    }
}
