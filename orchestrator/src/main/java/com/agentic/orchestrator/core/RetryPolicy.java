package com.agentic.orchestrator.core;

public record RetryPolicy(int maxRetries, long initialBackoffMillis, double backoffMultiplier) {

    public static RetryPolicy none() {
        return new RetryPolicy(0, 0, 1.0);
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(2, 200, 2.0);
    }

    public long backoffFor(int attempt) {
        return Math.round(initialBackoffMillis * Math.pow(backoffMultiplier, Math.max(0, attempt - 1)));
    }
}
