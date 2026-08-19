package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.core.Decision;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.Gate;
import com.agentic.orchestrator.core.Stage;
import com.agentic.orchestrator.core.StageResult;

import java.util.List;

public final class ArchitectureAgent implements Stage {

    @Override
    public String id() {
        return "architecture";
    }

    @Override
    public String description() {
        return "Confirm/extend the component design for the normalized spec";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("requirements");
    }

    @Override
    public Gate entryGate() {
        return ctx -> ctx.hasArtifact("normalizedSpec")
                ? Gate.GateResult.pass("normalized spec present")
                : Gate.GateResult.fail("requirements stage did not produce a normalized spec");
    }

    @Override
    public StageResult execute(ExecutionContext ctx) {
        String spec = ctx.getArtifact("normalizedSpec");

        String decision = "Zero-runtime-dependency service on JDK com.sun.net.httpserver; in-memory "
                + "ConcurrentHashMap store behind a UrlStore interface (swappable for a real DB later); "
                + "Base62-encoded auto-increment codes for generated links; fixed-window per-IP rate limiter "
                + "on the create endpoint; regex + URI-scheme validation on all input.";

        ctx.recordDecision(Decision.of(id(),
                "selected in-process HTTP server + in-memory store architecture",
                "keeps the prototype runnable offline with zero external services, while UrlStore stays an "
                        + "interface so persistence can be swapped later without touching any HTTP handler"));

        ctx.putArtifact("architectureDecision", decision);
        String preview = spec == null ? "n/a" : spec.substring(0, Math.min(70, spec.length()));
        return StageResult.success("architecture decided for spec: " + preview);
    }
}
