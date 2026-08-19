package com.agentic.orchestrator.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a {@link DependencyGraph} against an {@link ExecutionContext}.
 * Stages run level by level, in parallel within a level, and a failure only blocks its own dependents.
 */
public final class Orchestrator {

    private final DependencyGraph graph;
    private final PolicyEngine policyEngine;
    private final ApprovalGateway approvalGateway;
    private final AuditLogger auditLogger;
    private final MetricsCollector metricsCollector = new MetricsCollector();
    private final Map<String, StageStatus> statuses = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public Orchestrator(DependencyGraph graph, PolicyEngine policyEngine, ApprovalGateway approvalGateway,
                         AuditLogger auditLogger) {
        this.graph = graph;
        this.policyEngine = policyEngine;
        this.approvalGateway = approvalGateway;
        this.auditLogger = auditLogger;
        this.executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        graph.validate();
        for (Stage s : graph.stages()) {
            statuses.put(s.id(), StageStatus.PENDING);
        }
    }

    public RunReport run(ExecutionContext ctx) {
        auditLogger.log("orchestrator", AuditEvent.Type.RUN_START,
                "starting scenario '" + ctx.scenarioName() + "' run " + ctx.runId());

        Set<String> haltedStages = ConcurrentHashMap.newKeySet();
        List<List<Stage>> levels = graph.computeLevels(Set.of());
        executeLevels(levels, ctx, haltedStages);

        return finish(ctx);
    }

    /** Re-runs the given stage and everything downstream of it; leaves the rest of the run alone. */
    public RunReport replan(ExecutionContext ctx, String changedStageId, String rationale) {
        Set<String> affected = graph.transitiveDependents(changedStageId);
        affected.add(changedStageId);

        auditLogger.log("orchestrator", AuditEvent.Type.REPLAN,
                "re-planning from '" + changedStageId + "': " + rationale,
                Map.of("affectedStages", String.join(",", affected)));

        for (String id : affected) {
            statuses.put(id, StageStatus.PENDING);
        }

        Set<String> alreadySatisfied = new java.util.HashSet<>(statuses.keySet());
        alreadySatisfied.removeAll(affected);

        Set<String> haltedStages = ConcurrentHashMap.newKeySet();
        List<List<Stage>> levels = graph.computeLevels(alreadySatisfied);
        // drop anything not in the affected subgraph
        List<List<Stage>> scoped = levels.stream()
                .map(level -> level.stream().filter(s -> affected.contains(s.id())).toList())
                .filter(level -> !level.isEmpty())
                .toList();
        executeLevels(scoped, ctx, haltedStages);

        return finish(ctx);
    }

    private void executeLevels(List<List<Stage>> levels, ExecutionContext ctx, Set<String> haltedStages) {
        for (List<Stage> level : levels) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (Stage stage : level) {
                futures.add(executor.submit(() -> runStageWithHalt(stage, ctx, haltedStages)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    throw new RuntimeException("Unexpected orchestrator execution failure", e);
                }
            }
        }
    }

    private void runStageWithHalt(Stage stage, ExecutionContext ctx, Set<String> haltedStages) {
        boolean blockedByUpstream = stage.dependsOn().stream().anyMatch(haltedStages::contains);
        if (blockedByUpstream) {
            statuses.put(stage.id(), StageStatus.BLOCKED);
            haltedStages.add(stage.id());
            auditLogger.log(stage.id(), AuditEvent.Type.BLOCKED,
                    "safe-stop: upstream dependency did not complete successfully");
            return;
        }
        StageStatus outcome = runStage(stage, ctx);
        statuses.put(stage.id(), outcome);
        if (outcome == StageStatus.FAILED || outcome == StageStatus.BLOCKED) {
            haltedStages.add(stage.id());
        }
    }

    private StageStatus runStage(Stage stage, ExecutionContext ctx) {
        Gate.GateResult entry = stage.entryGate().evaluate(ctx);
        if (!entry.passed()) {
            auditLogger.log(stage.id(), AuditEvent.Type.ENTRY_GATE_FAIL, entry.reason());
            return StageStatus.FAILED;
        }

        if (stage.requiresApproval()) {
            statuses.put(stage.id(), StageStatus.AWAITING_APPROVAL);
            auditLogger.log(stage.id(), AuditEvent.Type.APPROVAL_REQUESTED,
                    "human approval requested before executing '" + stage.description() + "'");
            ApprovalGateway.ApprovalDecision decision =
                    approvalGateway.requestApproval(stage.id(), stage.description(), ctx);
            if (!decision.approved()) {
                auditLogger.log(stage.id(), AuditEvent.Type.APPROVAL_DENIED,
                        "denied by " + decision.approver() + (decision.note() == null ? "" : ": " + decision.note()));
                auditLogger.log(stage.id(), AuditEvent.Type.SAFE_STOP, "halting due to denied approval");
                return StageStatus.FAILED;
            }
            auditLogger.log(stage.id(), AuditEvent.Type.APPROVAL_GRANTED, "approved by " + decision.approver());
        }

        RetryPolicy policy = stage.retryPolicy();
        StageResult result = null;
        for (int attempt = 1; attempt <= policy.maxRetries() + 1; attempt++) {
            auditLogger.log(stage.id(), AuditEvent.Type.STAGE_START,
                    stage.description() + " (attempt " + attempt + ")");
            try {
                result = stage.execute(ctx);
            } catch (Exception e) {
                result = StageResult.fatal("uncaught exception: " + e.getMessage(), e);
            }

            if (result.isSuccess()) {
                if (validateSuccess(stage, ctx)) {
                    auditLogger.log(stage.id(), AuditEvent.Type.STAGE_SUCCESS, result.message());
                    return StageStatus.SUCCESS;
                } else {
                    result = StageResult.fatal("exit gate / policy validation failed after execution", null);
                    break;
                }
            }

            if (result.outcome() == StageOutcome.RECOVERABLE_FAILURE && attempt <= policy.maxRetries()) {
                long backoff = policy.backoffFor(attempt);
                auditLogger.log(stage.id(), AuditEvent.Type.STAGE_RETRY,
                        result.message() + " -- retrying in " + backoff + "ms");
                sleep(backoff);
                continue;
            }
            break;
        }

        auditLogger.log(stage.id(), AuditEvent.Type.STAGE_FAILED,
                result == null ? "unknown failure" : result.message());

        var fb = stage.fallback();
        if (fb.isPresent()) {
            auditLogger.log(stage.id(), AuditEvent.Type.FALLBACK_INVOKED,
                    "primary executor exhausted retries, invoking fallback");
            try {
                StageResult fbResult = fb.get().execute(ctx);
                if (fbResult.isSuccess() && validateSuccess(stage, ctx)) {
                    auditLogger.log(stage.id(), AuditEvent.Type.STAGE_SUCCESS,
                            "fallback succeeded: " + fbResult.message());
                    return StageStatus.SUCCESS;
                }
            } catch (Exception e) {
                auditLogger.log(stage.id(), AuditEvent.Type.STAGE_FAILED, "fallback also failed: " + e.getMessage());
            }
        }

        try {
            stage.rollback(ctx);
            auditLogger.log(stage.id(), AuditEvent.Type.ROLLBACK, "rolled back partial side effects");
        } catch (Exception e) {
            auditLogger.log(stage.id(), AuditEvent.Type.ROLLBACK, "rollback itself failed: " + e.getMessage());
        }

        auditLogger.log(stage.id(), AuditEvent.Type.SAFE_STOP,
                "halting this branch of the pipeline; independent branches continue");
        return StageStatus.FAILED;
    }

    private boolean validateSuccess(Stage stage, ExecutionContext ctx) {
        Gate.GateResult exit = stage.exitGate().evaluate(ctx);
        if (!exit.passed()) {
            auditLogger.log(stage.id(), AuditEvent.Type.EXIT_GATE_FAIL, exit.reason());
            return false;
        }
        List<PolicyViolation> violations = policyEngine.evaluate(stage.id(), ctx);
        boolean blocking = false;
        for (PolicyViolation v : violations) {
            auditLogger.log(stage.id(), AuditEvent.Type.POLICY_VIOLATION,
                    "[" + v.severity() + "] " + v.ruleName() + ": " + v.description());
            if (v.severity() == PolicyViolation.Severity.BLOCKING) {
                blocking = true;
            }
        }
        return !blocking;
    }

    private RunReport finish(ExecutionContext ctx) {
        auditLogger.log("orchestrator", AuditEvent.Type.RUN_END, "run finished");
        List<AuditEvent> events = auditLogger.events();
        MetricsCollector.RunMetrics metrics = metricsCollector.compute(events);
        boolean overallSuccess = statuses.values().stream()
                .allMatch(s -> s == StageStatus.SUCCESS || s == StageStatus.SKIPPED);
        return new RunReport(ctx.runId(), ctx.scenarioName(), overallSuccess,
                Map.copyOf(statuses), ctx.decisionLineage(), metrics, auditLogger.logFile());
    }

    public MetricsCollector metricsCollector() {
        return metricsCollector;
    }

    public void shutdown() {
        executor.shutdown();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
