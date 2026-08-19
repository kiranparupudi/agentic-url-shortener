package com.agentic.orchestrator.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Writes every stage event to a JSON-lines file and to the console.
 * {@link MetricsCollector} reads this back to compute its metrics.
 */
public final class AuditLogger implements AutoCloseable {

    private final String runId;
    private final Path logFile;
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    public AuditLogger(String runId, Path logDir) {
        this.runId = runId;
        try {
            Files.createDirectories(logDir);
            this.logFile = logDir.resolve("run-" + runId + ".jsonl");
            Files.deleteIfExists(logFile);
            Files.createFile(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void log(String stageId, AuditEvent.Type type, String message) {
        log(stageId, type, message, Map.of());
    }

    public synchronized void log(String stageId, AuditEvent.Type type, String message, Map<String, String> details) {
        AuditEvent event = new AuditEvent(Instant.now(), runId, stageId, type, message, details);
        events.add(event);
        System.out.printf("[%s] %-18s %-28s %s%n", event.timestamp(), type, stageId, message);
        try {
            Files.writeString(logFile, event.toJsonLine() + System.lineSeparator(),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<AuditEvent> events() {
        return new ArrayList<>(events);
    }

    public Path logFile() {
        return logFile;
    }

    @Override
    public void close() {
        // file handle is opened/closed per-write; nothing to release
    }
}
