package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.core.Decision;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.Gate;
import com.agentic.orchestrator.core.Stage;
import com.agentic.orchestrator.core.StageResult;

import java.util.List;

/** Final go/no-go gate. The one stage every scenario requires human approval for. */
public final class ReleaseReadinessAgent implements Stage {

    @Override
    public String id() {
        return "release-readiness";
    }

    @Override
    public String description() {
        return "Final go/no-go check: green build, docs present, no blocking policy violations";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("testing", "documentation");
    }

    @Override
    public boolean requiresApproval() {
        return true;
    }

    @Override
    public Gate entryGate() {
        return ctx -> {
            boolean buildGreen = Boolean.TRUE.equals(ctx.getArtifact("buildGreen"));
            boolean docsGenerated = Boolean.TRUE.equals(ctx.getArtifact("docsGenerated"));
            if (!buildGreen) {
                return Gate.GateResult.fail("build is not green");
            }
            if (!docsGenerated) {
                return Gate.GateResult.fail("documentation was not generated");
            }
            return Gate.GateResult.pass("build green and docs present");
        };
    }

    @Override
    public StageResult execute(ExecutionContext ctx) {
        String testSummary = ctx.getArtifact("testResultSummary");
        ctx.recordDecision(Decision.of(id(),
                "release marked GO",
                "build green, docs present, no blocking policy violations, and a human approved this checkpoint"));
        ctx.putArtifact("releaseDecision", "GO");
        return StageResult.success("release readiness: GO (" + testSummary + ")");
    }
}
