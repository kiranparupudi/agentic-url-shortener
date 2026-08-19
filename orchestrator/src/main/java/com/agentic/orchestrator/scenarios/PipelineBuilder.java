package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.agents.ArchitectureAgent;
import com.agentic.orchestrator.agents.DocumentationAgent;
import com.agentic.orchestrator.agents.ImplementationAgent;
import com.agentic.orchestrator.agents.ReleaseReadinessAgent;
import com.agentic.orchestrator.agents.RequirementsAgent;
import com.agentic.orchestrator.agents.TestingAgent;
import com.agentic.orchestrator.ai.LlmClient;
import com.agentic.orchestrator.ai.LlmClientFactory;
import com.agentic.orchestrator.core.ApprovalGateway;
import com.agentic.orchestrator.core.AuditLogger;
import com.agentic.orchestrator.core.DependencyGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Wires the fixed six-stage SDLC graph shared by all three scenarios. */
final class PipelineBuilder {

    private PipelineBuilder() {
    }

    static DependencyGraph build(String templateSet, String testTemplateSet, boolean simulateTransientTestFailure,
                                  ApprovalGateway approvalGateway, AuditLogger auditLogger, LlmClient llmClient) {
        DependencyGraph graph = new DependencyGraph();
        graph.addStage(new RequirementsAgent(approvalGateway, auditLogger, llmClient));
        graph.addStage(new ArchitectureAgent());
        graph.addStage(new ImplementationAgent(templateSet));
        graph.addStage(new TestingAgent(testTemplateSet, simulateTransientTestFailure));
        graph.addStage(new DocumentationAgent(templateSet));
        graph.addStage(new ReleaseReadinessAgent());
        return graph;
    }

    static boolean hasFlag(String[] args, String flag) {
        return Arrays.asList(args).contains(flag);
    }

    /**
     * Resolves the Claude client for {@code --use-claude}: returns null (deterministic fallback)
     * if the flag isn't set, or if it's set but ANTHROPIC_API_KEY isn't in the environment.
     */
    static LlmClient resolveLlmClient(String[] args) {
        if (!hasFlag(args, "--use-claude")) {
            return null;
        }
        return LlmClientFactory.fromEnvironment()
                .map(client -> {
                    System.out.println("[requirements] --use-claude set: RequirementsAgent will call Claude for real.");
                    return client;
                })
                .orElseGet(() -> {
                    System.out.println("[requirements] --use-claude set but ANTHROPIC_API_KEY is not in the "
                            + "environment - falling back to the deterministic heuristic.");
                    return null;
                });
    }

    /** Walks up from the current working directory to find the reactor root (has url-shortener/pom.xml). */
    static Path locateWorkspaceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("url-shortener").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate workspace root (expected an ancestor directory "
                + "containing url-shortener/pom.xml)");
    }
}
