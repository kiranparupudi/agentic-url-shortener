package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.ai.LlmClient;
import com.agentic.orchestrator.core.ApprovalGateway;
import com.agentic.orchestrator.core.AuditEvent;
import com.agentic.orchestrator.core.AuditLogger;
import com.agentic.orchestrator.core.Decision;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.Stage;
import com.agentic.orchestrator.core.StageResult;

import java.util.List;
import java.util.Locale;

/**
 * Flags ambiguous requirements and asks for clarification before normalizing
 * them. With an {@link LlmClient} supplied, ambiguity is judged by a real
 * Claude call instead of the keyword heuristic - see {@link #classifyWithClaude}.
 * Falls back to the heuristic if no client is given, or if the call fails,
 * so a network hiccup never breaks the pipeline.
 */
public final class RequirementsAgent implements Stage {

    private static final List<String> VAGUE_TRIGGERS = List.of(
            "add analytics", "make it better", "improve it", "faster", "nicer", "more reliable");
    private static final List<String> CLARIFYING_KEYWORDS = List.of(
            "referrer", "last access", "lastaccess", "click count", "granularity", "retention", "metric");

    private static final String CLAUDE_SYSTEM_PROMPT =
            "You are the requirements-understanding stage of a software engineering pipeline. "
                    + "Given a raw feature requirement, decide whether it is clear enough to implement "
                    + "directly, or too ambiguous/underspecified and needs a human to clarify scope first. "
                    + "Respond in exactly this format and nothing else:\n"
                    + "AMBIGUOUS: yes|no\n"
                    + "INTERPRETATION: <one sentence proposed interpretation - only include this line if AMBIGUOUS is yes>";

    private final ApprovalGateway approvalGateway;
    private final AuditLogger auditLogger;
    private final LlmClient llmClient;

    public RequirementsAgent(ApprovalGateway approvalGateway, AuditLogger auditLogger) {
        this(approvalGateway, auditLogger, null);
    }

    /**
     * Approval here is content-dependent, so it can't use the static
     * requiresApproval() flag - needs its own AuditLogger to log the events properly.
     */
    public RequirementsAgent(ApprovalGateway approvalGateway, AuditLogger auditLogger, LlmClient llmClient) {
        this.approvalGateway = approvalGateway;
        this.auditLogger = auditLogger;
        this.llmClient = llmClient;
    }

    @Override
    public String id() {
        return "requirements";
    }

    @Override
    public String description() {
        return "Interpret raw requirement, flag ambiguity, normalize into an engineering spec"
                + (llmClient != null ? " (Claude-backed)" : "");
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

        Classification classification = classify(raw, ctx);

        String normalizedSpec;
        if (classification.ambiguous()) {
            String question = "Requirement '" + raw.trim() + "' does not specify enough detail. "
                    + "Proposed interpretation: " + classification.proposedInterpretation() + ". Approve this interpretation?";

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
                    "resolved ambiguous requirement to: " + classification.proposedInterpretation(),
                    "operator (" + decision.approver() + ") approved the " + classification.source() + " interpretation"));
            if (classification.legacyAnalyticsGranularity()) {
                ctx.putArtifact("analyticsGranularity", "count+lastaccess");
            }
            normalizedSpec = classification.proposedInterpretation();
        } else {
            ctx.recordDecision(Decision.of(id(), "requirement interpreted as unambiguous, proceeding without a clarification checkpoint",
                    classification.rationale()));
            normalizedSpec = raw.trim();
        }

        ctx.putArtifact("normalizedSpec", normalizedSpec);
        return StageResult.success("requirement normalized (ambiguityDetected=" + classification.ambiguous() + ")");
    }

    private Classification classify(String raw, ExecutionContext ctx) {
        if (llmClient != null) {
            try {
                return classifyWithClaude(raw);
            } catch (Exception e) {
                ctx.recordDecision(Decision.of(id(),
                        "Claude call failed, falling back to the keyword heuristic: " + e.getMessage(),
                        "a network/API failure here shouldn't take down the whole pipeline"));
            }
        }
        return classifyWithHeuristic(raw);
    }

    private Classification classifyWithClaude(String raw) throws Exception {
        String response = llmClient.complete(CLAUDE_SYSTEM_PROMPT, raw.trim());
        boolean ambiguous = false;
        String interpretation = null;
        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "AMBIGUOUS:", 0, "AMBIGUOUS:".length())) {
                ambiguous = trimmed.substring("AMBIGUOUS:".length()).trim().equalsIgnoreCase("yes");
            } else if (trimmed.regionMatches(true, 0, "INTERPRETATION:", 0, "INTERPRETATION:".length())) {
                interpretation = trimmed.substring("INTERPRETATION:".length()).trim();
            }
        }
        if (ambiguous && (interpretation == null || interpretation.isBlank())) {
            throw new IllegalStateException("Claude said AMBIGUOUS: yes but gave no INTERPRETATION line");
        }
        return new Classification(ambiguous, interpretation, "Claude judged this requirement clear enough as written",
                "Claude-proposed", false);
    }

    private Classification classifyWithHeuristic(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean vague = VAGUE_TRIGGERS.stream().anyMatch(lower::contains);
        boolean alreadyDetailed = CLARIFYING_KEYWORDS.stream().anyMatch(lower::contains);
        boolean ambiguous = vague && !alreadyDetailed;
        String interpretation = ambiguous
                ? "track per-link click count plus last-accessed timestamp (no referrer breakdown, to keep scope minimal)"
                : null;
        return new Classification(ambiguous, interpretation,
                "no vague qualifiers detected, or sufficient technical detail was already present",
                "proposed", true);
    }

    private record Classification(boolean ambiguous, String proposedInterpretation, String rationale,
                                   String source, boolean legacyAnalyticsGranularity) {
    }
}
