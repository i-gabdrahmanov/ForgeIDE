package dev.forgeide.core.event;

import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.RunId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inbox messages consumed by the single-threaded {@code PipelineEngine} actor
 * (SD §3, T06). Posted by executors (on virtual threads) and by the UI.
 */
public sealed interface EngineCommand {

    RunId runId();

    record StepCompleted(RunId runId, String stepId, int iteration,
                          List<PendingQuestion> pendingQuestions) implements EngineCommand {
        public StepCompleted {
            pendingQuestions = List.copyOf(pendingQuestions);
        }
    }

    record StepFailed(RunId runId, String stepId, int iteration,
                       FailureReason reason, String detail) implements EngineCommand {
    }

    /**
     * @param detail    extra payload some answers carry beyond the plain option string: the
     *                   replacement prompt text for a judge escalation's {@code edit_prompt}
     *                   action, or the mandatory reason for its {@code override} action
     *                   (FR-11.3). Empty for a real {@code GateStep} answer and the plain
     *                   escalation actions.
     * @param diffAcked  the FR-5.3 "I looked at the diff" checkbox state; the engine — not just
     *                   the dialog that disables its own buttons — refuses to confirm a {@code
     *                   R2}-risk {@code GateStep} unless this is {@code true}, so the UI is
     *                   never the sole enforcement point for a security-relevant confirmation.
     */
    record GateAnswered(RunId runId, String stepId, String answer,
                         String user, Instant at, Optional<String> detail, boolean diffAcked) implements EngineCommand {
        public GateAnswered {
            detail = detail == null ? Optional.empty() : detail;
        }

        /** Convenience for the common case of no extra payload/diff-ack (keeps older call sites terse). */
        public GateAnswered(RunId runId, String stepId, String answer, String user, Instant at) {
            this(runId, stepId, answer, user, at, Optional.empty(), false);
        }

        /** Convenience for an escalation answer (has {@code detail} but no diff-ack concept). */
        public GateAnswered(RunId runId, String stepId, String answer, String user, Instant at,
                             Optional<String> detail) {
            this(runId, stepId, answer, user, at, detail, false);
        }
    }

    record QuestionsAnswered(RunId runId, String stepId,
                              Map<String, String> answers) implements EngineCommand {
        public QuestionsAnswered {
            answers = Map.copyOf(answers);
        }
    }

    record CancelRun(RunId runId) implements EngineCommand {
    }

    record RetryStep(RunId runId, String stepId) implements EngineCommand {
    }
}
