package dev.forgeide.core.event;

import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Outbound messages published by the engine after each transition (SD §2, §3, §6).
 * All payloads are immutable; consumers (UI ViewModel) never see the mutable
 * {@code PipelineRun}.
 */
public sealed interface EngineEvent {

    RunId runId();

    record RunUpdated(RunSnapshot snapshot) implements EngineEvent {
        @Override
        public RunId runId() {
            return snapshot.runId();
        }
    }

    /** Published when a {@code GateStep} becomes {@code WAITING_GATE} (SD §6). */
    record GateRequest(RunId runId, String stepId, String question,
                        List<String> options, List<Path> artifacts) implements EngineEvent {
        public GateRequest {
            options = List.copyOf(options);
            artifacts = List.copyOf(artifacts);
        }
    }

    /** Published when a step becomes {@code WAITING_INPUT} (FR-10.2). */
    record QuestionsPending(RunId runId, String stepId,
                             List<PendingQuestion> questions) implements EngineEvent {
        public QuestionsPending {
            questions = List.copyOf(questions);
        }
    }

    /** A security invariant tripped (Т-1/Т-7/Т-9/Т-13...); always paired with an audit entry. */
    record IncidentRaised(RunId runId, String stepId, FailureReason reason,
                          String detail) implements EngineEvent {
    }
}
