package dev.forgeide.core.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RunRecoveryTest {

    @Test
    void flipsARunningStepToFailedInterrupted() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a", "b"));
        run.step("a").transitionTo(StepStatus.PASSED);
        run.step("b").transitionTo(StepStatus.READY);
        run.step("b").transitionTo(StepStatus.RUNNING);
        run.step("b").startIteration();

        RunSnapshot recovered = RunRecovery.recoverInterrupted(run.snapshot()).orElseThrow();

        StepSnapshot a = recovered.steps().stream().filter(s -> s.stepId().equals("a")).findFirst().orElseThrow();
        StepSnapshot b = recovered.steps().stream().filter(s -> s.stepId().equals("b")).findFirst().orElseThrow();
        assertThat(a.status()).isEqualTo(StepStatus.PASSED);
        assertThat(b.status()).isEqualTo(StepStatus.FAILED);
        assertThat(b.failureReason()).contains(FailureReason.INTERRUPTED);
        assertThat(b.iteration()).isEqualTo(1); // preserved, only the status/reason change
        assertThat(recovered.status()).isEqualTo(RunStatus.RUNNING); // a plain blocked-step failure, same as any other
    }

    @Test
    void leavesWaitingStepsAlone() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("gate"));
        run.step("gate").transitionTo(StepStatus.WAITING_GATE);

        assertThat(RunRecovery.recoverInterrupted(run.snapshot())).isEmpty();
    }

    @Test
    void noOpWhenNothingWasRunning() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a"));
        run.step("a").markFailed(FailureReason.SCRIPT);

        assertThat(RunRecovery.recoverInterrupted(run.snapshot())).isEmpty();
    }

    @Test
    void clearsAnyPendingQuestionsOnTheRecoveredStep() {
        PipelineRun run = new PipelineRun(RunId.newId(), "feature-x", List.of("a"));
        run.step("a").transitionTo(StepStatus.RUNNING);

        RunSnapshot recovered = RunRecovery.recoverInterrupted(run.snapshot()).orElseThrow();

        assertThat(recovered.steps().get(0).pendingQuestions()).isEmpty();
        assertThat(recovered.steps().get(0).failureReason()).isEqualTo(Optional.of(FailureReason.INTERRUPTED));
    }
}
