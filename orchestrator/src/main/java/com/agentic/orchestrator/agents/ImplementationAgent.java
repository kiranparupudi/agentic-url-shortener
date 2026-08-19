package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.core.Decision;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.Gate;
import com.agentic.orchestrator.core.Stage;
import com.agentic.orchestrator.core.StageResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the generated .java files for a template set into url-shortener.
 * Non-greenfield sets are applied as a delta on top of the greenfield baseline.
 */
public final class ImplementationAgent implements Stage {

    private final String templateSet;
    private final ConcurrentHashMap<String, Optional<String>> lastRunBackup = new ConcurrentHashMap<>();

    public ImplementationAgent(String templateSet) {
        this.templateSet = templateSet;
    }

    @Override
    public String id() {
        return "implementation";
    }

    @Override
    public String description() {
        return "Generate/modify the url-shortener source for template set '" + templateSet + "'";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("architecture");
    }

    @Override
    public Gate exitGate() {
        return ctx -> {
            List<?> files = ctx.getArtifact("implementationFiles");
            return files != null && !files.isEmpty()
                    ? Gate.GateResult.pass("implementation produced " + files.size() + " file(s)")
                    : Gate.GateResult.fail("implementation produced no files");
        };
    }

    @Override
    public StageResult execute(ExecutionContext ctx) throws IOException {
        Path targetDir = ctx.workspaceRoot()
                .resolve("url-shortener").resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("agentic").resolve("urlshortener");
        Files.createDirectories(targetDir);

        List<String> written = new ArrayList<>();
        if (!templateSet.equals("greenfield")) {
            // re-derive the baseline every time, not just when it's missing - keeps runs
            // order-independent even if a different scenario ran last
            ctx.recordDecision(Decision.of(id(),
                    "re-derived from the greenfield baseline before applying the '" + templateSet + "' delta",
                    "keeps this run reproducible regardless of what an earlier scenario run left on disk"));
            written.addAll(writeFilesWithBackup("greenfield", targetDir));
        }

        List<String> deltaFiles = writeFilesWithBackup(templateSet, targetDir);
        written.addAll(deltaFiles);

        ctx.recordDecision(Decision.of(id(),
                "wrote/updated " + deltaFiles.size() + " file(s) for template set '" + templateSet + "'",
                "impacted files: " + deltaFiles));

        StringBuilder scan = new StringBuilder();
        for (String f : written) {
            scan.append(Files.readString(targetDir.resolve(f))).append('\n');
        }

        ctx.putArtifact("generatedSourceForScan", scan.toString());
        ctx.putArtifact("implementationChanged", true);
        ctx.putArtifact("implementationTargetDir", targetDir.toString());
        ctx.putArtifact("implementationFiles", deltaFiles);
        ctx.putArtifact("implementationTemplateSet", templateSet);

        return StageResult.success("wrote " + deltaFiles.size() + " source file(s): " + deltaFiles);
    }

    private List<String> writeFilesWithBackup(String set, Path targetDir) {
        return TemplateWriter.writeAllTracked(set, targetDir, (name, previousContent) ->
                lastRunBackup.put(targetDir.resolve(name).toString(), Optional.ofNullable(previousContent)));
    }

    @Override
    public void rollback(ExecutionContext ctx) {
        lastRunBackup.forEach((path, previous) -> {
            try {
                if (previous.isPresent()) {
                    Files.writeString(Path.of(path), previous.get());
                } else {
                    Files.deleteIfExists(Path.of(path));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        ctx.recordDecision(Decision.of(id(),
                "rolled back " + lastRunBackup.size() + " file(s) to their pre-stage state",
                "implementation stage failed validation after writing; undoing partial side effects keeps the "
                        + "working tree consistent for the next attempt"));
        lastRunBackup.clear();
    }
}
