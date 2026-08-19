package com.agentic.orchestrator.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Configurable synthetic {@link Stage} for exercising the orchestrator engine in isolation. */
final class TestStage implements Stage {

    private final String id;
    private final List<String> dependsOn;
    private final IntFunction<StageResult> behavior;
    private final AtomicInteger attempts = new AtomicInteger();
    boolean rolledBack = false;

    private Gate entryGate = Gate.alwaysPass();
    private Gate exitGate = Gate.alwaysPass();
    private RetryPolicy retryPolicy = RetryPolicy.none();
    private Optional<Stage> fallback = Optional.empty();
    private boolean requiresApproval = false;

    TestStage(String id, List<String> dependsOn, IntFunction<StageResult> behavior) {
        this.id = id;
        this.dependsOn = dependsOn;
        this.behavior = behavior;
    }

    static TestStage alwaysSucceeds(String id, List<String> dependsOn) {
        return new TestStage(id, dependsOn, attempt -> StageResult.success("ok"));
    }

    static TestStage alwaysFailsFatally(String id, List<String> dependsOn) {
        return new TestStage(id, dependsOn, attempt -> StageResult.fatal("boom", null));
    }

    TestStage withEntryGate(Gate gate) {
        this.entryGate = gate;
        return this;
    }

    TestStage withExitGate(Gate gate) {
        this.exitGate = gate;
        return this;
    }

    TestStage withRetryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    TestStage withFallback(Stage fb) {
        this.fallback = Optional.of(fb);
        return this;
    }

    TestStage withRequiresApproval(boolean value) {
        this.requiresApproval = value;
        return this;
    }

    int attemptCount() {
        return attempts.get();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return "test stage " + id;
    }

    @Override
    public List<String> dependsOn() {
        return dependsOn;
    }

    @Override
    public boolean requiresApproval() {
        return requiresApproval;
    }

    @Override
    public Gate entryGate() {
        return entryGate;
    }

    @Override
    public Gate exitGate() {
        return exitGate;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    @Override
    public Optional<Stage> fallback() {
        return fallback;
    }

    @Override
    public StageResult execute(ExecutionContext ctx) {
        return behavior.apply(attempts.incrementAndGet());
    }

    @Override
    public void rollback(ExecutionContext ctx) {
        rolledBack = true;
    }
}
