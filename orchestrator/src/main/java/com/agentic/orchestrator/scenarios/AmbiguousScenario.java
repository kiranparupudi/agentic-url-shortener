package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.core.ApprovalGateway;
import com.agentic.orchestrator.core.AuditLogger;
import com.agentic.orchestrator.core.AutoApproveApprovalGateway;
import com.agentic.orchestrator.core.AutoDenyApprovalGateway;
import com.agentic.orchestrator.core.DependencyGraph;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.InteractiveCliApprovalGateway;
import com.agentic.orchestrator.core.Orchestrator;
import com.agentic.orchestrator.core.PolicyEngine;
import com.agentic.orchestrator.core.RunReport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Ambiguous: "Add analytics to the URL shortener." is under-specified, so
 * RequirementsAgent pauses for approval before continuing. Deny it to see the safe-stop path.
 *
 * Run: mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.AmbiguousScenario
 * Add -Dexec.args=--auto-approve to auto-approve the clarification (skips the interactive prompt).
 */
public final class AmbiguousScenario {

    private static final String RAW_REQUIREMENT = "Add analytics to the URL shortener.";

    public static void main(String[] args) throws Exception {
        boolean autoApprove = PipelineBuilder.hasFlag(args, "--auto-approve");
        boolean autoDeny = PipelineBuilder.hasFlag(args, "--auto-deny");
        Path workspaceRoot = PipelineBuilder.locateWorkspaceRoot();
        String runId = "ambiguous-" + Instant.now().toEpochMilli();

        ApprovalGateway approvalGateway = autoDeny ? new AutoDenyApprovalGateway()
                : autoApprove ? new AutoApproveApprovalGateway() : new InteractiveCliApprovalGateway();
        AuditLogger auditLogger = new AuditLogger(runId, workspaceRoot.resolve("audit-logs"));
        PolicyEngine policyEngine = PolicyEngine.defaultGuardrails();

        DependencyGraph graph = PipelineBuilder.build("ambiguous", "greenfield-test", false, approvalGateway, auditLogger);
        ExecutionContext ctx = new ExecutionContext(runId, "ambiguous", workspaceRoot,
                Map.of("rawRequirement", RAW_REQUIREMENT));

        Orchestrator orchestrator = new Orchestrator(graph, policyEngine, approvalGateway, auditLogger);
        RunReport report = orchestrator.run(ctx);
        orchestrator.shutdown();

        System.out.println(report.renderSummary(orchestrator.metricsCollector()));
        if (!report.overallSuccess()) {
            System.out.println("Run halted by governance (denied clarification or a downstream gate). "
                    + "This is a valid, intended outcome for this scenario - see docs/SCENARIOS.md.");
            System.exit(1);
        }
    }
}
