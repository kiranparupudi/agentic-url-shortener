package com.agentic.orchestrator.core;

public record PolicyViolation(String ruleName, String stageId, String description, Severity severity) {

    public enum Severity {
        /** Fails the exit gate outright - stage cannot be considered done. */
        BLOCKING,
        /** Logged and surfaced in the report, does not stop the pipeline. */
        WARNING
    }
}
