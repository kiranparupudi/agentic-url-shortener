package com.agentic.orchestrator.core;

/** A precondition or postcondition check for a stage - used for both entry and exit gates. */
@FunctionalInterface
public interface Gate {

    GateResult evaluate(ExecutionContext ctx);

    static Gate alwaysPass() {
        return ctx -> GateResult.pass("no precondition");
    }

    record GateResult(boolean passed, String reason) {
        public static GateResult pass(String reason) {
            return new GateResult(true, reason);
        }

        public static GateResult fail(String reason) {
            return new GateResult(false, reason);
        }
    }
}
