package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestProjectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ManifestProjector projector = new ManifestProjector();

    @Test
    void projectWritesReadableManifestAndVerifyMatchesWhenUntouched(@TempDir Path repo) {
        RunSnapshot snapshot = snapshotOf(step("a", StepStatus.PASSED), step("b", StepStatus.RUNNING));

        String hash = projector.project(repo, "p", "feature-x", snapshot);
        Path manifest = ManifestProjector.manifestPath(repo, "p", "feature-x");
        assertThat(manifest).isRegularFile();

        Optional<String> diff = projector.verifyAndRestore(repo, "p", "feature-x", snapshot, hash);
        assertThat(diff).isEmpty();
    }

    @Test
    void verifyDetectsAndRestoresAnOutOfBandEdit(@TempDir Path repo) throws IOException {
        RunSnapshot snapshot = snapshotOf(step("lite-green", StepStatus.RUNNING), step("lite-verify", StepStatus.PENDING));
        String hash = projector.project(repo, "p", "feature-x", snapshot);

        Path manifest = ManifestProjector.manifestPath(repo, "p", "feature-x");
        ObjectNode tampered = (ObjectNode) MAPPER.readTree(manifest.toFile());
        for (var stepNode : tampered.get("steps")) {
            if (stepNode.get("id").asText().equals("lite-verify")) {
                ((ObjectNode) stepNode).put("status", "completed");
            }
        }
        Files.writeString(manifest, tampered.toString());

        Optional<String> diff = projector.verifyAndRestore(repo, "p", "feature-x", snapshot, hash);
        assertThat(diff).isPresent();
        assertThat(diff.get()).contains("lite-verify");

        ObjectNode restored = (ObjectNode) MAPPER.readTree(manifest.toFile());
        assertThat(statusOf(restored, "lite-verify")).isEqualTo("pending");
    }

    @Test
    void verifyTreatsAMissingManifestAsTampered(@TempDir Path repo) throws IOException {
        RunSnapshot snapshot = snapshotOf(step("a", StepStatus.RUNNING));
        String hash = projector.project(repo, "p", "feature-x", snapshot);
        Files.delete(ManifestProjector.manifestPath(repo, "p", "feature-x"));

        Optional<String> diff = projector.verifyAndRestore(repo, "p", "feature-x", snapshot, hash);
        assertThat(diff).isPresent();
        assertThat(ManifestProjector.manifestPath(repo, "p", "feature-x")).isRegularFile();
    }

    @Test
    void readOriginParsesAnExistingRecordAndIsEmptyOtherwise(@TempDir Path repo) throws IOException {
        assertThat(projector.readOrigin(repo, "p", "feature-x", "lite-green")).isEmpty();

        Path originsDir = ManifestProjector.statementsDir(repo, "p", "feature-x").resolve("_origins");
        Files.createDirectories(originsDir);
        Files.writeString(originsDir.resolve("lite-green.json"), "{\"closed_by\":\"subagent\"}");

        Optional<ObjectNode> origin = projector.readOrigin(repo, "p", "feature-x", "lite-green");
        assertThat(origin).isPresent();
        assertThat(origin.get().get("closed_by").asText()).isEqualTo("subagent");

        Files.writeString(originsDir.resolve("corrupt.json"), "not json");
        assertThat(projector.readOrigin(repo, "p", "feature-x", "corrupt")).isEmpty();
    }

    @Test
    void generateAndVerifyOverheadStaysUnderTwoSecondsEvenWithManySteps(@TempDir Path repo) {
        // NFR-4: the manifest-projection slice of the per-phase control budget.
        List<StepSnapshot> steps = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            steps.add(new StepSnapshot("step-" + i, StepStatus.PENDING, 0, Optional.empty(), List.of(), List.of(), List.of()));
        }
        RunSnapshot snapshot = new RunSnapshot(RunId.newId(), "feature-x", RunStatus.RUNNING, Optional.empty(), steps);

        long start = System.nanoTime();
        String hash = projector.project(repo, "p", "feature-x", snapshot);
        Optional<String> diff = projector.verifyAndRestore(repo, "p", "feature-x", snapshot, hash);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(diff).isEmpty();
        assertThat(elapsedMs).isLessThan(2_000);
    }

    private static String statusOf(ObjectNode manifest, String stepId) {
        for (var stepNode : manifest.get("steps")) {
            if (stepNode.get("id").asText().equals(stepId)) {
                return stepNode.get("status").asText();
            }
        }
        throw new AssertionError("no step " + stepId);
    }

    private static StepSnapshot step(String id, StepStatus status) {
        return new StepSnapshot(id, status, 0, Optional.empty(), List.of(), List.of(), List.of());
    }

    private static RunSnapshot snapshotOf(StepSnapshot... steps) {
        return new RunSnapshot(RunId.newId(), "feature-x", RunStatus.RUNNING, Optional.empty(), List.of(steps));
    }
}
