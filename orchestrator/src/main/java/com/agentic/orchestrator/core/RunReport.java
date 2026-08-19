package com.agentic.orchestrator.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record RunReport(String runId, String scenarioName, boolean overallSuccess,
                         Map<String, StageStatus> stageStatuses, List<Decision> decisionLineage,
                         MetricsCollector.RunMetrics metrics, Path auditLogFile) {

    public String renderSummary(MetricsCollector metricsCollector) {
        StringBuilder sb = new StringBuilder();
        sb.append("==================== RUN REPORT ====================\n");
        sb.append("Run id      : ").append(runId).append("\n");
        sb.append("Scenario    : ").append(scenarioName).append("\n");
        sb.append("Outcome     : ").append(overallSuccess ? "SUCCESS" : "INCOMPLETE / BLOCKED").append("\n");
        sb.append("Audit log   : ").append(auditLogFile).append("\n");
        sb.append("-- Stage statuses --\n");
        stageStatuses.forEach((k, v) -> sb.append(String.format("  %-22s %s%n", k, v)));
        sb.append("-- Decision lineage --\n");
        decisionLineage.forEach(d -> sb.append("  ").append(d).append("\n"));
        sb.append(metricsCollector.renderReport(metrics));
        sb.append("=====================================================\n");
        return sb.toString();
    }
}
