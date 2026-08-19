package com.agentic.orchestrator.core;

/** Auto-denies everything, so the safe-stop path can be demoed without typing 'n' by hand. */
public final class AutoDenyApprovalGateway implements ApprovalGateway {

    @Override
    public ApprovalDecision requestApproval(String stageId, String summary, ExecutionContext ctx) {
        System.out.println("[auto-deny] " + stageId + ": " + summary + " -> DENIED (non-interactive demo mode)");
        return new ApprovalDecision(false, "auto-deny-bot", "non-interactive demo mode");
    }
}
