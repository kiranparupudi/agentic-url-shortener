package com.agentic.orchestrator.core;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(Instant timestamp, String runId, String stageId, Type type, String message,
                          Map<String, String> details) {

    public enum Type {
        RUN_START, RUN_END,
        STAGE_START, STAGE_SUCCESS, STAGE_RETRY, STAGE_FAILED,
        ENTRY_GATE_FAIL, EXIT_GATE_FAIL, POLICY_VIOLATION,
        APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_DENIED,
        FALLBACK_INVOKED, ROLLBACK, SAFE_STOP, BLOCKED,
        REPLAN
    }

    String toJsonLine() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"timestamp\":\"").append(timestamp).append("\",");
        sb.append("\"runId\":\"").append(runId).append("\",");
        sb.append("\"stageId\":\"").append(escape(stageId)).append("\",");
        sb.append("\"type\":\"").append(type).append("\",");
        sb.append("\"message\":\"").append(escape(message)).append("\"");
        if (details != null && !details.isEmpty()) {
            sb.append(",\"details\":{");
            boolean first = true;
            for (var e : details.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
