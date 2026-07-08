package dev.forgeide.core.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepRunTest {

    @Test
    void startsPendingWithNoIterations() {
        StepRun run = new StepRun("s1");

        assertThat(run.status()).isEqualTo(StepStatus.PENDING);
        assertThat(run.iteration()).isZero();
        assertThat(run.failureReason()).isEmpty();
    }

    @Test
    void cannotTransitionToFailedWithoutAReason() {
        StepRun run = new StepRun("s1");

        assertThatThrownBy(() -> run.transitionTo(StepStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markFailedSetsStatusAndReasonTogether() {
        StepRun run = new StepRun("s1");

        run.markFailed(FailureReason.BUDGET);

        assertThat(run.status()).isEqualTo(StepStatus.FAILED);
        assertThat(run.failureReason()).contains(FailureReason.BUDGET);
    }

    @Test
    void leavingFailedClearsTheFailureReason() {
        StepRun run = new StepRun("s1");
        run.markFailed(FailureReason.STREAM);

        run.transitionTo(StepStatus.READY);

        assertThat(run.status()).isEqualTo(StepStatus.READY);
        assertThat(run.failureReason()).isEmpty();
    }

    @Test
    void awaitInputRequiresAtLeastOneQuestion() {
        StepRun run = new StepRun("s1");

        assertThatThrownBy(() -> run.awaitInput(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void awaitInputStoresQuestionsAndTransitioningAwayClearsThem() {
        StepRun run = new StepRun("s1");
        PendingQuestion question = new PendingQuestion("q1", "Which epic?", QuestionType.TEXT, List.of(), Optional.empty());

        run.awaitInput(List.of(question));
        assertThat(run.status()).isEqualTo(StepStatus.WAITING_INPUT);
        assertThat(run.pendingQuestions()).containsExactly(question);

        run.transitionTo(StepStatus.READY);
        assertThat(run.pendingQuestions()).isEmpty();
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        StepRun run = new StepRun("s1");
        run.startIteration();
        run.recordVerdict(new JudgeVerdict(1, Optional.of(true), true, "ok"));
        run.recordEvent(new AuditRef(1L, "step.started"));

        StepSnapshot snapshot = run.snapshot();

        assertThat(snapshot.stepId()).isEqualTo("s1");
        assertThat(snapshot.iteration()).isEqualTo(1);
        assertThat(snapshot.verdicts()).hasSize(1);
        assertThatThrownBy(() -> snapshot.verdicts().add(new JudgeVerdict(2, Optional.empty(), false, "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void restoreReproducesTheSnapshotExactlyIncludingAFailureReason() {
        StepRun original = new StepRun("s1");
        original.startIteration();
        original.startIteration();
        original.recordVerdict(new JudgeVerdict(1, Optional.of(false), false, "nope"));
        original.recordEvent(new AuditRef(7L, "step.running"));
        original.markFailed(FailureReason.BUDGET);

        StepRun restored = StepRun.restore(original.snapshot());

        assertThat(restored.snapshot()).isEqualTo(original.snapshot());
        assertThat(restored.status()).isEqualTo(StepStatus.FAILED);
        assertThat(restored.failureReason()).contains(FailureReason.BUDGET);
        assertThat(restored.iteration()).isEqualTo(2);
    }

    @Test
    void restorePreservesWaitingInputQuestions() {
        StepRun original = new StepRun("s1");
        PendingQuestion question = new PendingQuestion("q1", "Which epic?", QuestionType.TEXT, List.of(), Optional.empty());
        original.awaitInput(List.of(question));

        StepRun restored = StepRun.restore(original.snapshot());

        assertThat(restored.status()).isEqualTo(StepStatus.WAITING_INPUT);
        assertThat(restored.pendingQuestions()).containsExactly(question);
    }
}
