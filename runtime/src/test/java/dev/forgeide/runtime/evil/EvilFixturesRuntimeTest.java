package dev.forgeide.runtime.evil;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.git.GitScopeDiff;
import dev.forgeide.runtime.process.ProcessRunner;
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
 * T19 anti-bypass acceptance (SDD §7/§4.1) for the scenarios that need real plumbing: Т-4/Т-13
 * over a real git repository ({@link GitScopeDiff}, same stack as {@code
 * GitScopeDiffPipelineTest}) and Т-9 over a real spawned process ({@link ProcessRunner} wired in
 * as the engine's {@code ProcessSweepPort}). Т-1 (manifest tamper) already has its own dedicated
 * real-stack test, {@code ManifestProjectorTamperTest}; Т-7 (harness drift) its own, {@code
 * PipelineEngineHarnessDriftTest} (core) — both satisfy this same acceptance criterion and are
 * not repeated here.
 */
class EvilFixturesRuntimeTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void pushAttemptFailsHarmlesslyButTheStrayDeliveryFileFailsTheStepWithScope(
            @TempDir Path repo, @TempDir Path stateDir) throws IOException, InterruptedException {
        assumeGitAvailable();
        initRepo(repo);

        Path strayFile = repo.resolve("outside/delivered.txt");
        AgentStep work = agentStep("src/**");
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work));
        ProjectDefinition project = minimalProject(repo);
        FileStateStore stateStore = new FileStateStore(stateDir);

        try (PipelineEngine engine = engine(stateStore,
                EvilAgentRuntime.attemptsPushThenLeavesAStrayFile(strayFile), new GitScopeDiff(), null)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.SCOPE);

            AuditEvent incident = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("incident.scope")).findFirst().orElseThrow();
            assertThat(incident.payload().get("detail").asText()).contains("outside/delivered.txt");
        }
    }

    @Test
    void localCommitMovesHeadAndFailsTheStepWithScope(
            @TempDir Path repo, @TempDir Path stateDir) throws IOException, InterruptedException {
        assumeGitAvailable();
        initRepo(repo);

        AgentStep work = agentStep("src/**");
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work));
        ProjectDefinition project = minimalProject(repo);
        FileStateStore stateStore = new FileStateStore(stateDir);

        try (PipelineEngine engine = engine(stateStore,
                EvilAgentRuntime.commitsLocallyMovingHead(repo, repo.resolve("src/sneaky.txt")),
                new GitScopeDiff(), null)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.SCOPE);

            AuditEvent incident = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("incident.scope")).findFirst().orElseThrow();
            // src/sneaky.txt itself matches allowed_write — it is the HEAD move a plain glob-diff
            // would miss entirely that scope-diff (SR-6) still catches.
            assertThat(incident.payload().get("detail").asText()).contains(".git (HEAD moved");
        }
    }

    @Test
    void detachedProcessOutlivingThePhaseIsSweptAndRecordedAsAnIncident(
            @TempDir Path repo, @TempDir Path stateDir) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "go");

        EvilAgentRuntime.DetachedProcessSpawner spawner = new EvilAgentRuntime.DetachedProcessSpawner();
        AgentStep work = agentStep();
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work));
        ProjectDefinition project = minimalProject(repo);
        FileStateStore stateStore = new FileStateStore(stateDir);
        ProcessRunner processSweep = new ProcessRunner();

        try (PipelineEngine engine = engine(stateStore, spawner, null, processSweep)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.PASSED).orElse(false));
            assertThat(spawner.spawnedProcess()).isNotNull();

            until(() -> stateStore.loadAudit(runId).stream()
                    .anyMatch(e -> e.type().equals("incident.orphan_process")));
            AuditEvent incident = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("incident.orphan_process")).findFirst().orElseThrow();
            assertThat(incident.payload().get("pids")).anySatisfy(
                    n -> assertThat(n.asLong()).isEqualTo(spawner.spawnedProcess().pid()));

            until(() -> !spawner.spawnedProcess().isAlive());
        } finally {
            if (spawner.spawnedProcess() != null) {
                spawner.spawnedProcess().destroyForcibly();
            }
        }
    }

    private static AgentStep agentStep(String... allowedWrite) {
        return new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(allowedWrite), List.of(), RetryPolicy.DEFAULT, BUDGET);
    }

    private static PipelineEngine engine(FileStateStore stateStore, dev.forgeide.core.port.AgentRuntimePort agentRuntime,
                                          GitScopeDiff scopeDiff, ProcessRunner processSweep) {
        return new PipelineEngine(stateStore, agentRuntime, inv -> {
            throw new AssertionError("no script steps in this pipeline");
        }, ManifestProjectorPort.NOOP, scopeDiff == null ? dev.forgeide.core.port.ScopeDiffPort.NOOP : scopeDiff,
                SecretStorePort.NOOP, OutwardActionsPort.NOOP, HarnessGuardPort.NOOP,
                processSweep == null ? dev.forgeide.core.port.ProcessSweepPort.NOOP : processSweep);
    }

    private static ProjectDefinition minimalProject(Path repo) {
        return new ProjectDefinition(ProjectId.newId(), "evil-fixtures-test", repo,
                List.of(), Map.of(), CriticalityProfile.LOW,
                List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude"))));
    }

    private static void initRepo(Path repo) throws IOException, InterruptedException {
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/.keep"), "");
        run(repo, "add", "-A");
        run(repo, "commit", "-q", "-m", "initial");
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "go");
    }

    private static StepStatus statusOf(RunSnapshot snapshot, String stepId) {
        return snapshot.steps().stream().filter(s -> s.stepId().equals(stepId)).findFirst()
                .orElseThrow().status();
    }

    private static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000L * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within timeout");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private static void assumeGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            assumeTrue(p.waitFor() == 0, "git binary not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "git binary not available");
        }
    }

    private static void run(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed with " + exit);
        }
    }
}
