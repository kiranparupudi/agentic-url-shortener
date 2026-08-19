package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.core.ApprovalGateway;
import com.agentic.orchestrator.core.AuditEvent;
import com.agentic.orchestrator.core.AuditLogger;
import com.agentic.orchestrator.core.Decision;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.Stage;
import com.agentic.orchestrator.core.StageResult;

import java.util.List;
import java.util.Locale;

/** Flags ambiguous requirements and asks for clarification before normalizing them. */
public final class RequirementsAgent implements Stage {

    private static final List<String> VAGUE_TRIGGERS = List.of(
            "add analytics", "make it better", "improve it", "faster", "nicer", "more reliable");
    private static final List<String> CLARIFYING_KEYWORDS = List.of(
            "referrer", "last access", "lastaccess", "click count", "granularity", "retention", "metric");

    private final ApprovalGateway approvalGateway;
    private final AuditLogger auditLogger;

    /**
     * Approval here is content-dependent, so it can't use the static
     * requiresApproval() flag - needs its own AuditLogger to log the events properly.
     */
    public RequirementsAgent(ApprovalGateway approvalGateway, AuditLogger auditLogger) {
        this.approvalGateway = approvalGateway;
        this.auditLogger = auditLogger;
    }

    @Override
    public String id() {
        return "requirements";
    }

    @Override
    public String description() {
        return "Interpret raw requirement, flag ambiguity, normalize into an engineering spec";
    }

    @Override
    public List<String> dependsOn() {
        return List.of();
    }

    @Override
    public StageResult execute(ExecutionContext ctx) {
        String raw = ctx.input("rawRequirement");
        if (raw == null || raw.isBlank()) {
            return StageResult.fatal("no requirement text supplied in scenario input", null);
        }

        String lower = raw.toLowerCase(Locale.ROOT);
        boolean vague = VAGUE_TRIGGERS.stream().anyMatch(lower::contains);
        boolean alreadyDetailed = CLARIFYING_KEYWORDS.stream().anyMatch(lower::contains);
        boolean ambiguous = vague && !alreadyDetailed;

        String normalizedSpec;
        if (ambiguous) {
            String proposedInterpretation =
                    "track per-link click count plus last-accessed timestamp (no referrer breakdown, to keep scope minimal)";
            String question = "Requirement '" + raw.trim() + "' does not specify which analytics signal to expose. "
                    + "Proposed interpretation: " + proposedInterpretation + ". Approve this interpretation?";

            auditLogger.log(id(), AuditEvent.Type.APPROVAL_REQUESTED,
                    "clarification requested before normalizing an ambiguous requirement");
            ApprovalGateway.ApprovalDecision decision = approvalGateway.requestApproval(id(), question, ctx);
            if (!decision.approved()) {
                auditLogger.log(id(), AuditEvent.Type.APPROVAL_DENIED,
                        "denied by " + decision.approver() + (decision.note() == null ? "" : ": " + decision.note()));
                ctx.recordDecision(Decision.of(id(),
                        "requirement clarification denied by " + decision.approver(),
                        "cannot safely normalize an ambiguous requirement without operator sign-off; halting the run"));
                return StageResult.fatal("clarification denied - requirement remains ambiguous", null);
            }
            auditLogger.log(id(), AuditEvent.Type.APPROVAL_GRANTED, "approved by " + decision.approver());

            ctx.recordDecision(Decision.of(id(),
                    "resolved ambiguous analytics requirement to: " + proposedInterpretation,
                    "operator (" + decision.approver() + ") approved the proposed interpretation"));
            ctx.putArtifact("analyticsGranularity", "count+lastaccess");
            normalizedSpec = "Expose click analytics per short link: click count and last-accessed timestamp. "
                    + "No referrer breakdown in this iteration.";
        } else {
            ctx.recordDecision(Decision.of(id(),
                    "requirement interpreted as unambiguous, proceeding without a clarification checkpoint",
                    "no vague qualifiers detected, or sufficient technical detail was already present"));
            normalizedSpec = raw.trim();
        }

        ctx.putArtifact("normalizedSpec", normalizedSpec);
        return StageResult.success("requirement normalized (ambiguityDetected=" + ambiguous + ")");
    }
}
