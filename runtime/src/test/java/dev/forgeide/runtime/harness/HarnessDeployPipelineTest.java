package dev.forgeide.runtime.harness;

import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.runtime.state.FileStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T37 acceptance ("свежий проект: Import scaffold → Deploy harness → Start run проходит без
 * обходных путей"): exercises the exact {@link dev.forgeide.core.port.HarnessGuardPort} calls the
 * new "Deploy harness" button makes — {@link DefaultHarnessGuard#deploy} then {@link
 * PipelineEngine#start} — against the real filesystem/process stack (no UI), proving a fresh
 * project actually unblocks without the T20 trusted-edit-a-hook-file workaround {@code
 * docs/manual.md} §5 used to describe.
 */
class HarnessDeployPipelineTest {

    @Test
    void aRunHaltsOnPreflightBeforeDeployAndRunsCleanlyAfterIt(
            @TempDir Path project, @TempDir Path forgeideHome, @TempDir Path stateDir) throws IOException {
        assumePython3Available();
        writeValidHarness(project);

        ScriptStep noop = new ScriptStep("noop", List.of(), List.of("true"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(noop));
        ProjectDefinition proj = new ProjectDefinition(ProjectId.newId(), "deploy-test", project,
                List.of(), Map.of(), CriticalityProfile.LOW,
                List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude"))));

        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        FileStateStore stateStore = new FileStateStore(stateDir);

        try (PipelineEngine engine = new PipelineEngine(stateStore,
                (invocation, onEvent) -> { throw new AssertionError("no agent steps in this pipeline"); },
                inv -> new ScriptResult(0, "ok", ""),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, OutwardActionsPort.NOOP, guard)) {
            RunId runId = engine.start(proj, definition, "feature-before-deploy");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.PAUSED).orElse(false));

            assertThat(engine.snapshot(runId).orElseThrow().haltReason()).contains(RunHaltReason.HARNESS_PREFLIGHT);
        }

        var deployResult = guard.deploy(project);
        assertThat(deployResult.preflightPassed()).isTrue();

        try (PipelineEngine engine = new PipelineEngine(stateStore,
                (invocation, onEvent) -> { throw new AssertionError("no agent steps in this pipeline"); },
                inv -> new ScriptResult(0, "ok", ""),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, OutwardActionsPort.NOOP, guard)) {
            RunId runId = engine.start(proj, definition, "feature-after-deploy");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
        }
    }

    private static void writeValidHarness(Path project) throws IOException {
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/tdd-guard.py"), "print('guarding')\n");
        Files.writeString(harness.resolve("settings.hooks.json"), """
                {"hooks": {"SubagentStop": ["hooks/tdd-guard.py"]}}
                """);
    }

    private static void assumePython3Available() {
        try {
            Process p = new ProcessBuilder("python3", "--version").start();
            assumeTrue(p.waitFor() == 0, "python3 binary not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "python3 binary not available");
        }
    }

    private static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 2_000L * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within timeout");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
