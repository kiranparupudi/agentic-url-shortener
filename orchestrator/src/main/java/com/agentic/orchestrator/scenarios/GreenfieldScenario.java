package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.ai.LlmClient;
import com.agentic.orchestrator.core.ApprovalGateway;
import com.agentic.orchestrator.core.AuditLogger;
import com.agentic.orchestrator.core.AutoApproveApprovalGateway;
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
 * Greenfield: builds the URL shortener from a clear requirement, straight through all six stages.
 *
 * Run: mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.GreenfieldScenario
 * Add -Dexec.args=--auto-approve to skip the interactive approval prompt.
 * Add -Dexec.args=--use-claude to have RequirementsAgent call Claude for real (needs ANTHROPIC_API_KEY).
 */
public final class GreenfieldScenario {

    private static final String RAW_REQUIREMENT =
            "Build a URL shortener service with an API to create short links, redirect users to the original "
                    + "URL, and expose basic click analytics. It needs to be reliable enough for production use: "
                    + "validate input, rate-limit abusive callers, and support link expiration.";

    public static void main(String[] args) throws Exception {
        boolean autoApprove = PipelineBuilder.hasFlag(args, "--auto-approve");
        Path workspaceRoot = PipelineBuilder.locateWorkspaceRoot();
        String runId = "greenfield-" + Instant.now().toEpochMilli();

        ApprovalGateway approvalGateway = autoApprove ? new AutoApproveApprovalGateway() : new InteractiveCliApprovalGateway();
        AuditLogger auditLogger = new AuditLogger(runId, workspaceRoot.resolve("audit-logs"));
        PolicyEngine policyEngine = PolicyEngine.defaultGuardrails();
        LlmClient llmClient = PipelineBuilder.resolveLlmClient(args);

        DependencyGraph graph = PipelineBuilder.build("greenfield", "greenfield-test", false, approvalGateway, auditLogger, llmClient);
        ExecutionContext ctx = new ExecutionContext(runId, "greenfield", workspaceRoot,
                Map.of("rawRequirement", RAW_REQUIREMENT));

        Orchestrator orchestrator = new Orchestrator(graph, policyEngine, approvalGateway, auditLogger);
        RunReport report = orchestrator.run(ctx);
        orchestrator.shutdown();

        System.out.println(report.renderSummary(orchestrator.metricsCollector()));
        if (!report.overallSuccess()) {
            System.exit(1);
        }
    }
}
