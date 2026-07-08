package dev.forgeide.runtime.state;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** SDD FR-3.4: the IDE-startup sweep that turns an abandoned {@code RUNNING} step terminal. */
class StartupRecoveryTest {

    @Test
    void recoversAnAbandonedRunAndAppendsAnAuditEntry(@TempDir Path stateRoot) {
        FileStateStore store = new FileStateStore(stateRoot);
        RunId runId = RunId.newId();
        StepSnapshot passed = new StepSnapshot("ground", StepStatus.PASSED, 1, Optional.empty(),
                List.of(), List.of(), List.of());
        StepSnapshot running = new StepSnapshot("build", StepStatus.RUNNING, 1, Optional.empty(),
                List.of(), List.of(), List.of());
        store.save(new RunSnapshot(runId, "feature-x", RunStatus.RUNNING, Optional.empty(), List.of(passed, running)));

        List<RunId> recovered = StartupRecovery.recover(store);

        assertThat(recovered).containsExactly(runId);
        RunSnapshot after = store.load(runId).orElseThrow();
        assertThat(after.steps().stream().filter(s -> s.stepId().equals("ground")).findFirst().orElseThrow().status())
                .isEqualTo(StepStatus.PASSED);
        StepSnapshot recoveredBuild = after.steps().stream().filter(s -> s.stepId().equals("build")).findFirst().orElseThrow();
        assertThat(recoveredBuild.status()).isEqualTo(StepStatus.FAILED);
        assertThat(recoveredBuild.failureReason()).contains(FailureReason.INTERRUPTED);

        List<AuditEvent> audit = store.loadAudit(runId);
        assertThat(audit).extracting(AuditEvent::type).contains("run.recovered");
        AuditEvent recoveredEvent = audit.stream().filter(e -> e.type().equals("run.recovered")).findFirst().orElseThrow();
        assertThat(recoveredEvent.payload().get("interruptedSteps")).extracting(n -> n.asText()).containsExactly("build");
    }

    @Test
    void aCleanlyTerminatedRunIsLeftAlone() {
        FileStateStore store = new FileStateStore(newTempStateRoot());
        RunId runId = RunId.newId();
        StepSnapshot passed = new StepSnapshot("ground", StepStatus.PASSED, 1, Optional.empty(),
                List.of(), List.of(), List.of());
        store.save(new RunSnapshot(runId, "feature-x", RunStatus.COMPLETED, Optional.empty(), List.of(passed)));

        List<RunId> recovered = StartupRecovery.recover(store);

        assertThat(recovered).isEmpty();
        assertThat(store.loadAudit(runId)).isEmpty();
    }

    @Test
    void sweepsEveryRunAcrossEveryFeature(@TempDir Path stateRoot) {
        FileStateStore store = new FileStateStore(stateRoot);
        RunId abandoned1 = RunId.newId();
        RunId abandoned2 = RunId.newId();
        RunId clean = RunId.newId();
        StepSnapshot running = new StepSnapshot("s", StepStatus.RUNNING, 1, Optional.empty(), List.of(), List.of(), List.of());
        StepSnapshot done = new StepSnapshot("s", StepStatus.PASSED, 1, Optional.empty(), List.of(), List.of(), List.of());
        store.save(new RunSnapshot(abandoned1, "feature-a", RunStatus.RUNNING, Optional.empty(), List.of(running)));
        store.save(new RunSnapshot(abandoned2, "feature-b", RunStatus.RUNNING, Optional.empty(), List.of(running)));
        store.save(new RunSnapshot(clean, "feature-a", RunStatus.COMPLETED, Optional.empty(), List.of(done)));

        List<RunId> recovered = StartupRecovery.recover(store);

        assertThat(recovered).containsExactlyInAnyOrder(abandoned1, abandoned2);
    }

    private static Path newTempStateRoot() {
        try {
            return java.nio.file.Files.createTempDirectory("forgeide-startup-recovery-test");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
