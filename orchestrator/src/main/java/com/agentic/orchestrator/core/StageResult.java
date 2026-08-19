package com.agentic.orchestrator.core;

public record StageResult(StageOutcome outcome, String message, Throwable error) {

    public static StageResult success(String message) {
        return new StageResult(StageOutcome.SUCCESS, message, null);
    }

    public static StageResult recoverable(String message, Throwable error) {
        return new StageResult(StageOutcome.RECOVERABLE_FAILURE, message, error);
    }

    public static StageResult fatal(String message, Throwable error) {
        return new StageResult(StageOutcome.FATAL_FAILURE, message, error);
    }

    public boolean isSuccess() {
        return outcome == StageOutcome.SUCCESS;
    }
}
