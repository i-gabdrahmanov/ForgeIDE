package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T15 GWT acceptance (Т-1/SR-2): "процесс-имитатор агента дописывает в manifest.json строку
 * 'lite-verify: completed'" mid-phase → engine catches the hash mismatch after the phase, fails
 * the running step {@code FAILED(tampered)}, restores the projection from the SoT, and records
 * {@code incident.tamper} with a diff in the audit trail. Exercises the real {@link
 * PipelineEngine} + {@link FileStateStore} + {@link ManifestProjector} stack end-to-end (not a
 * fixture), since the whole point is the projection round-tripping through the actual
 * filesystem the way an external hook process would see it.
 */
class ManifestProjectorTamperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void agentTamperingWithTheProjectionMidPhaseFailsTheStepAndRestoresTheManifest(
            @TempDir Path repo, @TempDir Path stateDir) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("lite-green.md"), "go green");

        AgentStep liteGreen = new AgentStep("lite-green", List.of(), "claude", Path.of("prompts/lite-green.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        ScriptStep liteVerify = new ScriptStep("lite-verify", List.of("lite-green"), List.of("echo", "ok"),
                Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(liteGreen, liteVerify));

        ProjectDefinition project = new ProjectDefinition(ProjectId.newId(), "tamper-test", repo,
                List.of(), Map.of(), CriticalityProfile.LOW,
                List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude"))));

        FileStateStore stateStore = new FileStateStore(stateDir);
        ManifestProjector manifestProjector = new ManifestProjector();

        AgentRuntimePort tamperingAgent = (invocation, onEvent) -> {
            tamperManifest(repo, "p", "feature-x");
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "lite-green");
            return new AgentResult(0, Optional.of(json), new dev.forgeide.core.port.TokenUsage(1, 1),
                    Path.of("raw.log"));
        };
        ScriptRunnerPort scriptRunner = inv -> new ScriptResult(0, "ok", "");

        try (PipelineEngine engine = new PipelineEngine(stateStore, tamperingAgent, scriptRunner, manifestProjector)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId)
                    .map(s -> statusOf(s, "lite-green") == StepStatus.FAILED)
                    .orElse(false));

            var snapshot = engine.snapshot(runId).orElseThrow();
            var liteGreenRun = snapshot.steps().stream().filter(s -> s.stepId().equals("lite-green")).findFirst().orElseThrow();
            assertThat(liteGreenRun.failureReason()).contains(FailureReason.TAMPERED);
            // lite-verify's dependsOn (lite-green PASSED) is never satisfied — the tampered
            // manifest content the imitator wrote never reached anything the engine trusts.
            assertThat(statusOf(snapshot, "lite-verify")).isEqualTo(StepStatus.PENDING);

            List<AuditEvent> audit = stateStore.loadAudit(runId);
            AuditEvent incident = audit.stream().filter(e -> e.type().equals("incident.tamper"))
                    .findFirst().orElseThrow();
            assertThat(incident.stepId()).isEqualTo("lite-green");
            assertThat(incident.payload().get("reason").asText()).isEqualTo("TAMPERED");
            String diff = incident.payload().get("detail").asText();
            assertThat(diff).contains("lite-verify");

            // Restored: the manifest on disk must no longer claim lite-verify is completed.
            JsonNode restored = MAPPER.readTree(ManifestProjector.manifestPath(repo, "p", "feature-x").toFile());
            String liteVerifyStatus = statusInManifest(restored, "lite-verify");
            assertThat(liteVerifyStatus).isEqualTo("pending");
        }
    }

    private static void tamperManifest(Path repo, String pipelineId, String featureSlug) {
        Path path = ManifestProjector.manifestPath(repo, pipelineId, featureSlug);
        try {
            ObjectNode manifest = (ObjectNode) MAPPER.readTree(path.toFile());
            for (JsonNode step : manifest.get("steps")) {
                if (step.get("id").asText().equals("lite-verify")) {
                    ((ObjectNode) step).put("status", "completed");
                }
            }
            Files.writeString(path, manifest.toPrettyString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String statusInManifest(JsonNode manifest, String stepId) {
        for (JsonNode step : manifest.get("steps")) {
            if (step.get("id").asText().equals(stepId)) {
                return step.get("status").asText();
            }
        }
        throw new AssertionError("no step " + stepId + " in restored manifest");
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
}
