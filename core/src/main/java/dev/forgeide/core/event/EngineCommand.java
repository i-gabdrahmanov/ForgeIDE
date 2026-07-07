package dev.forgeide.core.event;

import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.RunId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    record GateAnswered(RunId runId, String stepId, String answer,
                         String user, Instant at) implements EngineCommand {
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
