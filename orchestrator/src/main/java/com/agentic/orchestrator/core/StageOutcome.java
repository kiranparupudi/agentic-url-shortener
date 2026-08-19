package com.agentic.orchestrator.core;

public enum StageOutcome {
    /** Stage did what it set out to do. */
    SUCCESS,
    /** Transient/environmental failure - worth a bounded retry. */
    RECOVERABLE_FAILURE,
    /** Not worth retrying - go straight to fallback/rollback/safe-stop. */
    FATAL_FAILURE
}
