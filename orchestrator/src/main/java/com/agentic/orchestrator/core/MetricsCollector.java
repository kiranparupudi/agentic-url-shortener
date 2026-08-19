package com.agentic.orchestrator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Computes success rate, retry/rollback counts, MTTR, and latency from the audit event stream. */
public final class MetricsCollector {

    public record RunMetrics(int totalStages, int succeeded, int failed, int blocked,
                              int retryCount, int rollbackCount, int approvalsRequested, int approvalsDenied,
                              double successRatePct, Duration meanTimeToRecover, Duration endToEndLatency) {
    }

    public RunMetrics compute(List<AuditEvent> events) {
        Instant runStart = null;
        Instant runEnd = null;
        int retries = 0, rollbacks = 0, approvalsRequested = 0, approvalsDenied = 0;
        Map<String, Instant> firstFailureAt = new HashMap<>();
        List<Duration> recoveryTimes = new ArrayList<>();
        java.util.Set<String> allStages = new java.util.LinkedHashSet<>();
        java.util.Set<String> succeededStages = new java.util.LinkedHashSet<>();
        java.util.Set<String> failedStages = new java.util.LinkedHashSet<>();
        java.util.Set<String> blockedStages = new java.util.LinkedHashSet<>();

        for (AuditEvent e : events) {
            if (e.type() == AuditEvent.Type.RUN_START) runStart = e.timestamp();
            if (e.type() == AuditEvent.Type.RUN_END) runEnd = e.timestamp();
            if (!"orchestrator".equals(e.stageId()) && !"policy".equals(e.stageId())) {
                allStages.add(e.stageId());
            }
            switch (e.type()) {
                case STAGE_RETRY -> retries++;
                case ROLLBACK -> rollbacks++;
                case APPROVAL_REQUESTED -> approvalsRequested++;
                case APPROVAL_DENIED -> approvalsDenied++;
                case STAGE_FAILED -> {
                    failedStages.add(e.stageId());
                    firstFailureAt.putIfAbsent(e.stageId(), e.timestamp());
                }
                case BLOCKED -> blockedStages.add(e.stageId());
                case STAGE_SUCCESS -> {
                    succeededStages.add(e.stageId());
                    failedStages.remove(e.stageId());
                    Instant failedAt = firstFailureAt.get(e.stageId());
                    if (failedAt != null) {
                        recoveryTimes.add(Duration.between(failedAt, e.timestamp()));
                    }
                }
                default -> { /* not metric-relevant */ }
            }
        }

        Duration mttr = recoveryTimes.isEmpty()
                ? Duration.ZERO
                : recoveryTimes.stream().reduce(Duration.ZERO, Duration::plus).dividedBy(recoveryTimes.size());

        Duration e2e = (runStart != null && runEnd != null) ? Duration.between(runStart, runEnd) : Duration.ZERO;

        int total = allStages.size();
        double successRate = total == 0 ? 0.0 : (succeededStages.size() * 100.0) / total;

        return new RunMetrics(total, succeededStages.size(), failedStages.size(), blockedStages.size(),
                retries, rollbacks, approvalsRequested, approvalsDenied, successRate, mttr, e2e);
    }

    public String renderReport(RunMetrics m) {
        StringBuilder sb = new StringBuilder();
        sb.append("---- Reliability Metrics ----\n");
        sb.append(String.format("Stages total/succeeded/failed/blocked : %d / %d / %d / %d%n",
                m.totalStages(), m.succeeded(), m.failed(), m.blocked()));
        sb.append(String.format("Success rate                          : %.1f%%%n", m.successRatePct()));
        sb.append(String.format("Retries triggered                     : %d%n", m.retryCount()));
        sb.append(String.format("Rollbacks triggered                   : %d%n", m.rollbackCount()));
        sb.append(String.format("Approvals requested/denied             : %d / %d%n",
                m.approvalsRequested(), m.approvalsDenied()));
        sb.append(String.format("Mean time to recover (MTTR)           : %d ms%n", m.meanTimeToRecover().toMillis()));
        sb.append(String.format("End-to-end latency                    : %d ms%n", m.endToEndLatency().toMillis()));
        return sb.toString();
    }
}
