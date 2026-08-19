package com.agentic.orchestrator.core;

import java.util.ArrayList;
import java.util.List;

/** Registry of policy rules checked against every stage's exit gate. */
public final class PolicyEngine {

    private final List<PolicyRule> rules = new ArrayList<>();

    public PolicyEngine register(PolicyRule rule) {
        rules.add(rule);
        return this;
    }

    public List<PolicyViolation> evaluate(String stageId, ExecutionContext ctx) {
        List<PolicyViolation> violations = new ArrayList<>();
        for (PolicyRule rule : rules) {
            PolicyViolation v = rule.check(stageId, ctx);
            if (v != null) {
                violations.add(v);
            }
        }
        return violations;
    }

    public static PolicyEngine defaultGuardrails() {
        PolicyEngine engine = new PolicyEngine();

        engine.register(PolicyRule.of("no-hardcoded-secrets", (stageId, ctx) -> {
            String code = ctx.getArtifact("generatedSourceForScan");
            if (code != null && (code.contains("password=\"") || code.contains("apiKey=\"") || code.matches("(?s).*(?i)secret\\s*=\\s*\"[^\"]+\".*"))) {
                return new PolicyViolation("no-hardcoded-secrets", stageId,
                        "Generated source appears to contain a hardcoded credential/secret literal",
                        PolicyViolation.Severity.BLOCKING);
            }
            return null;
        }));

        engine.register(PolicyRule.of("tests-required-for-new-endpoints", (stageId, ctx) -> {
            // only check at release time - testing hasn't run yet when implementation's gate fires
            if (!"release-readiness".equals(stageId)) {
                return null;
            }
            Boolean testsExist = ctx.getArtifact("testsExistForImplementation");
            Boolean implementationChanged = ctx.getArtifact("implementationChanged");
            if (Boolean.TRUE.equals(implementationChanged) && !Boolean.TRUE.equals(testsExist)) {
                return new PolicyViolation("tests-required-for-new-endpoints", stageId,
                        "Implementation changed but no corresponding test artifact was produced",
                        PolicyViolation.Severity.BLOCKING);
            }
            return null;
        }));

        engine.register(PolicyRule.of("release-requires-green-build", (stageId, ctx) -> {
            if ("release-readiness".equals(stageId)) {
                Boolean buildGreen = ctx.getArtifact("buildGreen");
                if (!Boolean.TRUE.equals(buildGreen)) {
                    return new PolicyViolation("release-requires-green-build", stageId,
                            "Release readiness cannot pass without a green build/test run",
                            PolicyViolation.Severity.BLOCKING);
                }
            }
            return null;
        }));

        return engine;
    }
}
