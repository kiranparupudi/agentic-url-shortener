package com.agentic.orchestrator.agents;

import com.agentic.orchestrator.core.Decision;
import com.agentic.orchestrator.core.ExecutionContext;
import com.agentic.orchestrator.core.Gate;
import com.agentic.orchestrator.core.Stage;
import com.agentic.orchestrator.core.StageResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the test files for this template set and runs {@code mvn test} for real.
 * {@code simulateTransientFailureOnce} fakes one failed attempt so the retry path can be demoed.
 */
public final class TestingAgent implements Stage {

    private final String testTemplateSet;
    private final boolean simulateTransientFailureOnce;
    private final AtomicInteger attempts = new AtomicInteger();

    public TestingAgent(String testTemplateSet, boolean simulateTransientFailureOnce) {
        this.testTemplateSet = testTemplateSet;
        this.simulateTransientFailureOnce = simulateTransientFailureOnce;
    }

    @Override
    public String id() {
        return "testing";
    }

    @Override
    public String description() {
        return "Write/refresh tests for '" + testTemplateSet + "' and run the build";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("implementation");
    }

    @Override
    public Gate entryGate() {
        return ctx -> Boolean.TRUE.equals(ctx.getArtifact("implementationChanged"))
                ? Gate.GateResult.pass("implementation completed")
                : Gate.GateResult.fail("no implementation artifact to test against");
    }

    @Override
    public StageResult execute(ExecutionContext ctx) throws IOException, InterruptedException {
        int attempt = attempts.incrementAndGet();

        Path targetTestDir = ctx.workspaceRoot()
                .resolve("url-shortener").resolve("src").resolve("test").resolve("java")
                .resolve("com").resolve("agentic").resolve("urlshortener");

        List<String> written = new ArrayList<>();
        if (!testTemplateSet.equals("greenfield-test")) {
            // write the baseline test files first, then let this scenario's set overwrite them
            written.addAll(TemplateWriter.writeAll("greenfield-test", targetTestDir));
        }
        written.addAll(TemplateWriter.writeAll(testTemplateSet, targetTestDir));

        ctx.recordDecision(Decision.of(id(),
                "wrote/updated test file(s): " + written,
                "test coverage must track the implementation delta so regressions are caught, not just re-asserted"));
        ctx.putArtifact("testsExistForImplementation", true);

        if (simulateTransientFailureOnce && attempt == 1) {
            return StageResult.recoverable(
                    "test runner cold-start timeout acquiring an ephemeral port (simulated transient infra failure)",
                    null);
        }

        ProcessResult result = runMavenTest(ctx.workspaceRoot());
        ctx.putArtifact("buildGreen", result.success());
        ctx.putArtifact("testResultSummary", result.summary());

        if (!result.success()) {
            return StageResult.fatal("mvn test failed: " + result.summary(), null);
        }
        return StageResult.success(result.summary());
    }

    private ProcessResult runMavenTest(Path workspaceRoot) throws IOException, InterruptedException {
        String mvnCmd = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
        ProcessBuilder pb = new ProcessBuilder(mvnCmd, "-q", "-o", "-pl", "url-shortener", "test")
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(false, "mvn test timed out after 120s");
        }
        int exit = process.exitValue();
        String tail = output.length() > 800 ? output.substring(output.length() - 800) : output;
        return new ProcessResult(exit == 0, exit == 0 ? "mvn test passed" : ("mvn test failed (exit=" + exit + "): " + tail));
    }

    private record ProcessResult(boolean success, String summary) {
    }
}
