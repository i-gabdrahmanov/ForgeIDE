package dev.forgeide.core.event;

import dev.forgeide.core.project.RiskLevel;
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

    /**
     * Published when a {@code GateStep} becomes {@code WAITING_GATE} (SD §6), and also — with
     * the same shared dialog infrastructure (FR-11.3) — when a judge exhausts its {@code
     * fail_policy} and escalates to a human. {@code risk} drives the FR-5.3 diff-ack checkbox
     * for real gates (escalations always pass {@code R1}, which does not force it); {@code
     * errorsHistory} is only non-empty for an escalation (the judge's accumulated failure detail
     * per iteration) and is untrusted the moment it includes an LLM-judge verdict.
     */
    record GateRequest(RunId runId, String stepId, String question, List<String> options,
                        List<Path> artifacts, RiskLevel risk, List<String> errorsHistory) implements EngineEvent {
        public GateRequest {
            options = List.copyOf(options);
            artifacts = List.copyOf(artifacts);
            errorsHistory = List.copyOf(errorsHistory);
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

    /**
     * T21/FR-8.4 reply to {@code EngineCommand.JudgeDryRunRequested}: the verdict a "прогнать
     * судью" click produced, for the inspector panel to show — never persisted to {@code
     * run.json}/{@code StepRun}, {@code requestId} is the only way a caller that fired more than
     * one dry-run in a row tells its replies apart.
     */
    record JudgeDryRunResult(RunId runId, String stepId, String requestId, boolean passed,
                              String detail) implements EngineEvent {
    }

    /** T21/FR-8.5 reply to {@code EngineCommand.PromptPreviewRequested}. */
    record PromptPreviewReady(RunId runId, String stepId, String requestId,
                               String renderedPrompt) implements EngineEvent {
    }
}
