package com.agentic.orchestrator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full engine coverage - parallel execution, retry, fallback, rollback, gates, approvals, and re-plan. */
class OrchestratorTest {

    @TempDir
    Path tempDir;

    private ExecutionContext newContext(String runId) {
        return new ExecutionContext(runId, "unit-test", tempDir, Map.of());
    }

    private Orchestrator newOrchestrator(DependencyGraph graph) {
        AuditLogger logger = new AuditLogger("run-" + System.nanoTime(), tempDir.resolve("logs"));
        return new Orchestrator(graph, new PolicyEngine(), new AutoApproveApprovalGateway(), logger);
    }

    @Test
    void exhaustedRetriesFailStageAndBlockDependent() {
        TestStage upstream = new TestStage("upstream", List.of(),
                attempt -> StageResult.recoverable("still broken", null))
                .withRetryPolicy(new RetryPolicy(1, 1, 1.0));
        TestStage downstream = TestStage.alwaysSucceeds("downstream", List.of("upstream"));
        DependencyGraph graph = new DependencyGraph().addStage(upstream).addStage(downstream);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r1"));
        orchestrator.shutdown();

        assertFalse(report.overallSuccess());
        assertEquals(2, upstream.attemptCount(), "1 initial + 1 retry, then exhausted");
        assertEquals(StageStatus.FAILED, report.stageStatuses().get("upstream"));
        assertEquals(StageStatus.BLOCKED, report.stageStatuses().get("downstream"));
        assertEquals(0, downstream.attemptCount(), "never actually executed");
    }

    @Test
    void fallbackRescuesAFatallyFailedStage() {
        TestStage fallback = TestStage.alwaysSucceeds("primary-fallback", List.of());
        TestStage primary = TestStage.alwaysFailsFatally("primary", List.of()).withFallback(fallback);
        DependencyGraph graph = new DependencyGraph().addStage(primary);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r2"));
        orchestrator.shutdown();

        assertTrue(report.overallSuccess());
        assertEquals(StageStatus.SUCCESS, report.stageStatuses().get("primary"));
        assertFalse(primary.rolledBack, "fallback succeeded, no need to roll back");
    }

    @Test
    void entryGateFailureBlocksExecutionAndDependents() {
        TestStage stage = TestStage.alwaysSucceeds("gated", List.of())
                .withEntryGate(ctx -> Gate.GateResult.fail("precondition never met in this test"));
        TestStage dependent = TestStage.alwaysSucceeds("after-gate", List.of("gated"));
        DependencyGraph graph = new DependencyGraph().addStage(stage).addStage(dependent);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r3"));
        orchestrator.shutdown();

        assertEquals(0, stage.attemptCount());
        assertEquals(StageStatus.FAILED, report.stageStatuses().get("gated"));
        assertEquals(StageStatus.BLOCKED, report.stageStatuses().get("after-gate"));
    }

    @Test
    void deniedApprovalSafeStopsWithoutExecuting() {
        TestStage stage = TestStage.alwaysSucceeds("needs-signoff", List.of()).withRequiresApproval(true);
        DependencyGraph graph = new DependencyGraph().addStage(stage);

        AuditLogger logger = new AuditLogger("run-deny", tempDir.resolve("logs2"));
        ApprovalGateway alwaysDeny = (stageId, summary, ctx) ->
                new ApprovalGateway.ApprovalDecision(false, "test-denier", "no");
        Orchestrator orchestrator = new Orchestrator(graph, new PolicyEngine(), alwaysDeny, logger);

        RunReport report = orchestrator.run(newContext("r4"));
        orchestrator.shutdown();

        assertFalse(report.overallSuccess());
        assertEquals(0, stage.attemptCount());
        assertEquals(StageStatus.FAILED, report.stageStatuses().get("needs-signoff"));
    }

    @Test
    void independentBranchKeepsRunningWhenSiblingFails() {
        TestStage failing = TestStage.alwaysFailsFatally("a", List.of());
        TestStage independent = TestStage.alwaysSucceeds("b", List.of());
        TestStage dependsOnFailing = TestStage.alwaysSucceeds("c", List.of("a"));
        DependencyGraph graph = new DependencyGraph().addStage(failing).addStage(independent).addStage(dependsOnFailing);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r5"));
        orchestrator.shutdown();

        assertEquals(StageStatus.FAILED, report.stageStatuses().get("a"));
        assertEquals(StageStatus.SUCCESS, report.stageStatuses().get("b"));
        assertEquals(StageStatus.BLOCKED, report.stageStatuses().get("c"));
    }

    @Test
    void independentStagesInSameLevelBothSucceed() {
        TestStage a = TestStage.alwaysSucceeds("a", List.of());
        TestStage b = TestStage.alwaysSucceeds("b", List.of());
        DependencyGraph graph = new DependencyGraph().addStage(a).addStage(b);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r6"));
        orchestrator.shutdown();

        assertTrue(report.overallSuccess());
        assertEquals(StageStatus.SUCCESS, report.stageStatuses().get("a"));
        assertEquals(StageStatus.SUCCESS, report.stageStatuses().get("b"));
    }

    @Test
    void recoverableFailureIsRetriedThenSucceeds() {
        TestStage stage = new TestStage("flaky", List.of(),
                attempt -> attempt == 1
                        ? StageResult.recoverable("transient", null)
                        : StageResult.success("recovered"))
                .withRetryPolicy(new RetryPolicy(2, 1, 1.0));
        DependencyGraph graph = new DependencyGraph().addStage(stage);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r7"));
        orchestrator.shutdown();

        assertTrue(report.overallSuccess());
        assertEquals(2, stage.attemptCount());
        assertEquals(1, report.metrics().retryCount());
    }

    @Test
    void rollbackAndSafeStopWhenNoFallbackAvailable() {
        TestStage stage = TestStage.alwaysFailsFatally("doomed", List.of());
        TestStage dependent = TestStage.alwaysSucceeds("dependent", List.of("doomed"));
        DependencyGraph graph = new DependencyGraph().addStage(stage).addStage(dependent);

        Orchestrator orchestrator = newOrchestrator(graph);
        RunReport report = orchestrator.run(newContext("r8"));
        orchestrator.shutdown();

        assertFalse(report.overallSuccess());
        assertTrue(stage.rolledBack);
        assertEquals(StageStatus.BLOCKED, report.stageStatuses().get("dependent"));
    }

    @Test
    void replanOnlyReExecutesTheAffectedSubgraph() {
        TestStage a = TestStage.alwaysSucceeds("a", List.of());
        TestStage b = TestStage.alwaysSucceeds("b", List.of("a"));
        TestStage c = TestStage.alwaysSucceeds("c", List.of("b"));
        DependencyGraph graph = new DependencyGraph().addStage(a).addStage(b).addStage(c);

        Orchestrator orchestrator = newOrchestrator(graph);
        ExecutionContext ctx = newContext("r9");
        RunReport first = orchestrator.run(ctx);
        assertTrue(first.overallSuccess());
        assertEquals(1, a.attemptCount());
        assertEquals(1, b.attemptCount());
        assertEquals(1, c.attemptCount());

        TestStage newB = TestStage.alwaysSucceeds("b", List.of("a"));
        graph.addStage(newB);

        RunReport second = orchestrator.replan(ctx, "b", "upstream artifact changed");
        orchestrator.shutdown();

        assertTrue(second.overallSuccess());
        assertEquals(1, a.attemptCount(), "unaffected upstream stage must not be re-run");
        assertEquals(1, newB.attemptCount());
        assertEquals(2, c.attemptCount(), "downstream dependent of the changed stage must be re-run");
    }
}
