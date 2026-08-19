package com.agentic.orchestrator.core;

/** A guardrail checked against a stage's output, separate from its own exit gate. */
public interface PolicyRule {

    String name();

    PolicyViolation check(String stageId, ExecutionContext ctx);

    static PolicyRule of(String name, java.util.function.BiFunction<String, ExecutionContext, PolicyViolation> fn) {
        return new PolicyRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public PolicyViolation check(String stageId, ExecutionContext ctx) {
                return fn.apply(stageId, ctx);
            }
        };
    }
}
