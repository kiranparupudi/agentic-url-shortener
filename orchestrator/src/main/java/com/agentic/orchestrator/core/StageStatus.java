package com.agentic.orchestrator.core;

public enum StageStatus {
    PENDING,
    AWAITING_APPROVAL,
    RUNNING,
    RETRYING,
    SUCCESS,
    ROLLED_BACK,
    FAILED,
    BLOCKED,
    SKIPPED
}
