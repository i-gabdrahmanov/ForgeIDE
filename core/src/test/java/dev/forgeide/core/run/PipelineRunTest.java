package dev.forgeide.core.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineRunTest {

    @Test
    void rejectsDuplicateStepIds() {
        assertThatThrownBy(() -> new PipelineRun(RunId.newId(), "feature-x", List.of("a", "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAtLeastOneStep() {
        assertThatThrownBy(() -> new PipelineRun(RunId.newId(), "feature-x", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsRunningWithNoHaltReason() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a", "b"));

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.haltReason()).isEmpty();
        assertThat(run.step("a").status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void unknownStepIdThrows() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a"));

        assertThatThrownBy(() -> run.step("missing")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void pauseAndStopCarryATypedHaltReason() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a"));

        run.pause(RunHaltReason.ENGINE_ERROR);
        assertThat(run.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(run.haltReason()).contains(RunHaltReason.ENGINE_ERROR);

        run.stop(RunHaltReason.HARNESS_DRIFT);
        assertThat(run.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(run.haltReason()).contains(RunHaltReason.HARNESS_DRIFT);
    }

    @Test
    void completingClearsAnyPriorHaltReason() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a"));
        run.pause(RunHaltReason.ENGINE_ERROR);

        run.complete();

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.haltReason()).isEmpty();
    }

    @Test
    void snapshotReflectsAllSteps() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a", "b"));
        run.step("a").transitionTo(StepStatus.READY);

        RunSnapshot snapshot = run.snapshot();

        assertThat(snapshot.steps()).hasSize(2);
        assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("a")).findFirst().orElseThrow().status())
                .isEqualTo(StepStatus.READY);
    }
}
