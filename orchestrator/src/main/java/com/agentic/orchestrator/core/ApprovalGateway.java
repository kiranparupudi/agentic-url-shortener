package com.agentic.orchestrator.core;

@FunctionalInterface
public interface ApprovalGateway {

    /** Asks for approval before a high-impact action runs. Denying it safe-stops the stage. */
    ApprovalDecision requestApproval(String stageId, String summary, ExecutionContext ctx);

    record ApprovalDecision(boolean approved, String approver, String note) {
    }
}
