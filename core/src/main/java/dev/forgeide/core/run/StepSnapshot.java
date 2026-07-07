package dev.forgeide.core.run;

import java.util.List;
import java.util.Optional;

public record StepSnapshot(
        String stepId,
        StepStatus status,
        int iteration,
        Optional<FailureReason> failureReason,
        List<PendingQuestion> pendingQuestions,
        List<JudgeVerdict> verdicts,
        List<AuditRef> events
) {

    public StepSnapshot {
        pendingQuestions = List.copyOf(pendingQuestions);
        verdicts = List.copyOf(verdicts);
        events = List.copyOf(events);
    }
}
