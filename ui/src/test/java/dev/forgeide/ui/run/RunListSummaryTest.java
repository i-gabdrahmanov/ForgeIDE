package dev.forgeide.ui.run;

import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RunListSummaryTest {

    @Test
    void countsPassedFailedAndTotalIterationsAcrossSteps() {
        RunSnapshot snapshot = new RunSnapshot(RunId.newId(), "feature-x", RunStatus.RUNNING, Optional.empty(), List.of(
                step("a", StepStatus.PASSED, 1),
                step("b", StepStatus.FAILED, 3),
                step("c", StepStatus.RUNNING, 2)));

        RunListSummary.Summary summary = RunListSummary.summarize(snapshot);

        assertThat(summary.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(summary.totalSteps()).isEqualTo(3);
        assertThat(summary.passedSteps()).isEqualTo(1);
        assertThat(summary.failedSteps()).isEqualTo(1);
        assertThat(summary.totalIterations()).isEqualTo(6);
    }

    @Test
    void emptyRunSummarizesToZeroes() {
        RunSnapshot snapshot = new RunSnapshot(RunId.newId(), "feature-x", RunStatus.COMPLETED, Optional.empty(),
                List.of(step("a", StepStatus.PASSED, 1)));

        RunListSummary.Summary summary = RunListSummary.summarize(snapshot);

        assertThat(summary.totalSteps()).isEqualTo(1);
        assertThat(summary.failedSteps()).isZero();
    }

    private StepSnapshot step(String id, StepStatus status, int iteration) {
        Optional<FailureReason> failure = status == StepStatus.FAILED ? Optional.of(FailureReason.SCRIPT) : Optional.empty();
        return new StepSnapshot(id, status, iteration, failure, List.of(), List.of(), List.of());
    }
}
