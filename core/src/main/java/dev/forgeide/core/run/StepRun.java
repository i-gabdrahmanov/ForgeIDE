package dev.forgeide.core.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable runtime state of one step. Mutated only from the engine's actor thread
 * (SD §2); everything else reads a {@link StepSnapshot}.
 */
public final class StepRun {

    private final String stepId;
    private StepStatus status;
    private int iteration;
    private FailureReason failureReason;
    private List<PendingQuestion> pendingQuestions = List.of();
    private final List<JudgeVerdict> verdicts = new ArrayList<>();
    private final List<AuditRef> events = new ArrayList<>();

    public StepRun(String stepId) {
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        this.status = StepStatus.PENDING;
        this.iteration = 0;
    }

    public String stepId() {
        return stepId;
    }

    public StepStatus status() {
        return status;
    }

    public int iteration() {
        return iteration;
    }

    public Optional<FailureReason> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public List<PendingQuestion> pendingQuestions() {
        return List.copyOf(pendingQuestions);
    }

    public List<JudgeVerdict> verdicts() {
        return List.copyOf(verdicts);
    }

    public List<AuditRef> events() {
        return List.copyOf(events);
    }

    /**
     * Non-FAILED transition. Clears any prior failure reason and pending questions
     * unless the new status is itself {@code WAITING_INPUT}.
     */
    public void transitionTo(StepStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == StepStatus.FAILED) {
            throw new IllegalArgumentException("use markFailed(reason) for FAILED status");
        }
        this.status = status;
        this.failureReason = null;
        if (status != StepStatus.WAITING_INPUT) {
            this.pendingQuestions = List.of();
        }
    }

    public void markFailed(FailureReason reason) {
        this.status = StepStatus.FAILED;
        this.failureReason = Objects.requireNonNull(reason, "reason");
    }

    public void awaitInput(List<PendingQuestion> questions) {
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("awaitInput requires at least one question");
        }
        this.status = StepStatus.WAITING_INPUT;
        this.failureReason = null;
        this.pendingQuestions = List.copyOf(questions);
    }

    public void startIteration() {
        this.iteration++;
    }

    public void recordVerdict(JudgeVerdict verdict) {
        verdicts.add(Objects.requireNonNull(verdict, "verdict"));
    }

    public void recordEvent(AuditRef ref) {
        events.add(Objects.requireNonNull(ref, "ref"));
    }

    public StepSnapshot snapshot() {
        return new StepSnapshot(stepId, status, iteration, failureReason(), pendingQuestions(), verdicts(), events());
    }
}
