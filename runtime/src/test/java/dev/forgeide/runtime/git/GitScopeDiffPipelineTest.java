package dev.forgeide.runtime.git;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.state.FileStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T16 GWT acceptance (Т-13/SR-6): "запись файла вне allowed_write → FAILED(scope) со списком
 * файлов" — exercised end-to-end against the real {@link PipelineEngine} + {@link FileStateStore}
 * + {@link GitScopeDiff} stack (not a fixture), same spirit as T15's {@code
 * ManifestProjectorTamperTest} for the tamper-check.
 */
class GitScopeDiffPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void writingOutsideAllowedWriteFailsTheStepWithScopeAndListsTheFile(
            @TempDir Path repo, @TempDir Path stateDir) throws IOException, InterruptedException {
        assumeGitAvailable();
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
        run(repo, "commit", "--allow-empty", "-q", "-m", "initial");

        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("lite-green.md"), "go green");

        AgentStep liteGreen = new AgentStep("lite-green", List.of(), "claude", Path.of("prompts/lite-green.md"),
                List.of(), List.of("src/**"), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(liteGreen));

        ProjectDefinition project = new ProjectDefinition(ProjectId.newId(), "scope-test", repo,
                List.of(), Map.of(), CriticalityProfile.LOW,
                List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude"))));

        FileStateStore stateStore = new FileStateStore(stateDir);
        GitScopeDiff scopeDiff = new GitScopeDiff();

        AgentRuntimePort strayWritingAgent = (invocation, onEvent) -> {
            try {
                Files.createDirectories(repo.resolve("outside"));
                Files.writeString(repo.resolve("outside/leak.txt"), "written by a phase with no business here\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "lite-green");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        };

        try (PipelineEngine engine = new PipelineEngine(stateStore, strayWritingAgent, inv -> {
            throw new AssertionError("no script steps in this pipeline");
        }, ManifestProjectorPort.NOOP, scopeDiff, SecretStorePort.NOOP)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId)
                    .map(s -> statusOf(s, "lite-green") == StepStatus.FAILED)
                    .orElse(false));

            var snapshot = engine.snapshot(runId).orElseThrow();
            var liteGreenRun = snapshot.steps().stream().filter(s -> s.stepId().equals("lite-green")).findFirst().orElseThrow();
            assertThat(liteGreenRun.failureReason()).contains(FailureReason.SCOPE);

            List<AuditEvent> audit = stateStore.loadAudit(runId);
            AuditEvent incident = audit.stream().filter(e -> e.type().equals("incident.scope"))
                    .findFirst().orElseThrow();
            assertThat(incident.stepId()).isEqualTo("lite-green");
            assertThat(incident.payload().get("reason").asText()).isEqualTo("SCOPE");
            assertThat(incident.payload().get("detail").asText()).contains("outside/leak.txt");

            // A blocked retry (T11/T16: scope violations require investigation, not a blind retry).
            assertThat(Files.exists(repo.resolve("outside/leak.txt"))).isTrue();
        }
    }

    private static StepStatus statusOf(dev.forgeide.core.run.RunSnapshot snapshot, String stepId) {
        return snapshot.steps().stream().filter(s -> s.stepId().equals(stepId)).findFirst()
                .orElseThrow().status();
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
