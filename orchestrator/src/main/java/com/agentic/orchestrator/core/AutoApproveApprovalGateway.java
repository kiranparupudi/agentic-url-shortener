package com.agentic.orchestrator.core;

/** Auto-approves everything, for scripted runs. Still logs the approval events like a real one would. */
public final class AutoApproveApprovalGateway implements ApprovalGateway {

    @Override
    public ApprovalDecision requestApproval(String stageId, String summary, ExecutionContext ctx) {
        System.out.println("[auto-approve] " + stageId + ": " + summary + " -> APPROVED (non-interactive mode)");
        return new ApprovalDecision(true, "auto-approve-bot", "non-interactive demo mode");
    }
}
