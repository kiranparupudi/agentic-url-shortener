package com.agentic.orchestrator.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State shared across all stages in one run - artifacts keyed by name, plus
 * the decision lineage. Thread-safe, since stages in the same level run at the same time.
 */
public final class ExecutionContext {

    private final String runId;
    private final String scenarioName;
    private final Path workspaceRoot;
    private final Map<String, Object> artifacts = new ConcurrentHashMap<>();
    private final List<Decision> decisionLineage = new CopyOnWriteArrayList<>();
    private final Map<String, Object> inputs;

    public ExecutionContext(String runId, String scenarioName, Path workspaceRoot, Map<String, Object> inputs) {
        this.runId = runId;
        this.scenarioName = scenarioName;
        this.workspaceRoot = workspaceRoot;
        this.inputs = Map.copyOf(inputs);
    }

    public String runId() {
        return runId;
    }

    public String scenarioName() {
        return scenarioName;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    @SuppressWarnings("unchecked")
    public <T> T input(String key) {
        return (T) inputs.get(key);
    }

    public void putArtifact(String key, Object value) {
        artifacts.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getArtifact(String key) {
        return (T) artifacts.get(key);
    }

    public boolean hasArtifact(String key) {
        return artifacts.containsKey(key);
    }

    public void invalidateArtifact(String key) {
        artifacts.remove(key);
    }

    public Map<String, Object> artifactsSnapshot() {
        return Map.copyOf(artifacts);
    }

    public void recordDecision(Decision decision) {
        decisionLineage.add(decision);
    }

    public List<Decision> decisionLineage() {
        return List.copyOf(decisionLineage);
    }
}
