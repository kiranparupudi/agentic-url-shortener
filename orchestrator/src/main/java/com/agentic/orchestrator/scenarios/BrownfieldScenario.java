package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.agents.DocumentationAgent;
import com.agentic.orchestrator.agents.ImplementationAgent;
import com.agentic.orchestrator.agents.TestingAgent;
import com.agentic.orchestrator.ai.LlmClient;
import com.agentic.orchestrator.core.ApprovalGateway;
import com.agentic.orchestrator.core.AuditLogger;
import com.agentic.orchestrator.core.AutoApproveApprovalGateway;
import com.agentic.orchestrator.core.Decision;
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
 * Brownfield: ships a bug fix in pass 1, then re-plans mid-run when scope
 * grows to add custom vanity aliases in pass 2.
 *
 * Run: mvn -pl orchestrator exec:java -Dexec.mainClass=com.agentic.orchestrator.scenarios.BrownfieldScenario
 * Add -Dexec.args=--auto-approve to skip the interactive approval prompts.
 * Add -Dexec.args=--use-claude to have RequirementsAgent call Claude for real (needs ANTHROPIC_API_KEY).
 */
public final class BrownfieldScenario {

    private static final String RAW_REQUIREMENT =
            "Enhance the existing URL shortener: we've noticed analytics look wrong on expired links - "
                    + "investigate and fix. Ship that first, it's affecting reporting today.";

    public static void main(String[] args) throws Exception {
        boolean autoApprove = PipelineBuilder.hasFlag(args, "--auto-approve");
        Path workspaceRoot = PipelineBuilder.locateWorkspaceRoot();
        String runId = "brownfield-" + Instant.now().toEpochMilli();

        ApprovalGateway approvalGateway = autoApprove ? new AutoApproveApprovalGateway() : new InteractiveCliApprovalGateway();
        AuditLogger auditLogger = new AuditLogger(runId, workspaceRoot.resolve("audit-logs"));
        PolicyEngine policyEngine = PolicyEngine.defaultGuardrails();
        LlmClient llmClient = PipelineBuilder.resolveLlmClient(args);

        DependencyGraph graph = PipelineBuilder.build(
                "brownfield-bugfix-only", "brownfield-bugfix-only-test", true, approvalGateway, auditLogger, llmClient);
        ExecutionContext ctx = new ExecutionContext(runId, "brownfield", workspaceRoot,
                Map.of("rawRequirement", RAW_REQUIREMENT));

        Orchestrator orchestrator = new Orchestrator(graph, policyEngine, approvalGateway, auditLogger);

        System.out.println("---- PASS 1: hotfix only (expect one simulated retry) ----");
        RunReport pass1 = orchestrator.run(ctx);
        System.out.println(pass1.renderSummary(orchestrator.metricsCollector()));

        if (!pass1.overallSuccess()) {
            System.out.println("Pass 1 did not complete successfully - stopping before the re-plan.");
            orchestrator.shutdown();
            System.exit(1);
            return;
        }

        ctx.recordDecision(Decision.of("orchestrator",
                "scope expanded after the hotfix shipped: also add custom vanity alias support",
                "product asked for this immediately after pass 1 landed; re-planning keeps decision lineage "
                        + "and the audit trail continuous instead of starting an unrelated new run"));

        // swap in the fuller agents, then re-plan from "implementation"
        graph.addStage(new ImplementationAgent("brownfield"));
        graph.addStage(new TestingAgent("brownfield-test", false));
        graph.addStage(new DocumentationAgent("brownfield"));

        System.out.println("---- PASS 2: re-plan from 'implementation' after scope change ----");
        RunReport pass2 = orchestrator.replan(ctx, "implementation",
                "add custom vanity alias support on top of the shipped hotfix");
        orchestrator.shutdown();

        System.out.println(pass2.renderSummary(orchestrator.metricsCollector()));
        if (!pass2.overallSuccess()) {
            System.exit(1);
        }
    }
}
