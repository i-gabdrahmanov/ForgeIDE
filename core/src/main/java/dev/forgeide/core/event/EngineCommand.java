package dev.forgeide.core.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.HarnessDriftAction;
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

    /**
     * @param user who answered (OS user, mirrors {@link GateAnswered}'s FR-5.1 audit trail —
     *              FR-10.6 wants the same "кто, когда" on {@code question.answered}).
     * @param at    when they answered.
     */
    record QuestionsAnswered(RunId runId, String stepId, Map<String, String> answers,
                              String user, Instant at) implements EngineCommand {
        public QuestionsAnswered {
            answers = Map.copyOf(answers);
        }

        /** Convenience for call sites that don't care who/when (tests, internal replay) — mirrors
         * {@link GateAnswered}'s terse overload. */
        public QuestionsAnswered(RunId runId, String stepId, Map<String, String> answers) {
            this(runId, stepId, answers, "unknown", Instant.now());
        }
    }

    /**
     * T15 scope: an {@code _origins/<stepId>.json} evidence record a SubagentStop hook
     * (state-recorder) left next to the manifest projection during this phase — informational
     * only, handled by appending an {@code evidence.origin} audit entry; unlike every other
     * command here it never drives a step-status transition.
     */
    record EvidenceObserved(RunId runId, String stepId, int iteration, ObjectNode payload) implements EngineCommand {
    }

    /**
     * T19/SR-9/Т-9: pids the post-phase orphan sweep force-killed (a {@code nohup}/{@code setsid}
     * escapee whose cwd sat under the project, still alive after the phase's own process group
     * was killed) — informational only, same shape as {@link EvidenceObserved}: appends an {@code
     * incident.orphan_process} audit entry and never drives a step-status transition.
     */
    record OrphanProcessesSwept(RunId runId, String stepId, int iteration, List<Long> pids) implements EngineCommand {
        public OrphanProcessesSwept {
            pids = List.copyOf(pids);
        }
    }

    /**
     * T17: dispatch outcome for an {@code OutwardStep} — the branch it pushed (consulted by a
     * later outward step's stacked-PR base resolution, {@code PipelineEngine#stackedPrBase}) and
     * the external refs each action produced (PR URL, Jira comment id, …) for the audit trail /
     * artifacts panel. Never emitted for a step that failed — that path is the ordinary {@link
     * StepFailed} with {@link dev.forgeide.core.run.FailureReason#SCRIPT}.
     */
    record OutwardCompleted(RunId runId, String stepId, int iteration, String branch,
                             Map<String, String> resultRefs) implements EngineCommand {
        public OutwardCompleted {
            resultRefs = Map.copyOf(resultRefs);
        }
    }

    /**
     * T18: resolves a run {@code STOPPED(harness-drift)} (SR-8) — a human either accepts the
     * drifted harness content as the new baseline ({@link HarnessDriftAction#ACCEPT}) or restores
     * it from the last known-good IDE harness cache ({@link HarnessDriftAction#ROLLBACK}).
     * Ignored unless the run is currently stopped for exactly that reason.
     *
     * @param user who resolved it, {@code at} when — same "кто, когда" shape as {@link GateAnswered}.
     */
    record HarnessDriftResolved(RunId runId, HarnessDriftAction action, String user, Instant at) implements EngineCommand {
        /** Convenience for call sites that don't care who/when (tests, internal replay). */
        public HarnessDriftResolved(RunId runId, HarnessDriftAction action) {
            this(runId, action, "unknown", Instant.now());
        }
    }

    record CancelRun(RunId runId) implements EngineCommand {
    }

    record RetryStep(RunId runId, String stepId) implements EngineCommand {
    }

    /**
     * T20/FR-8.2 trusted prompt edit, submitted by the tile inspector while a run is live:
     * {@code stepId} is the {@code AgentStep} or {@code JudgeStep} (its {@code llmJudge}) whose
     * prompt file changed — never a raw {@code templateKey}, the engine resolves that itself so
     * the UI does not need to know about {@code per_task_loop} namespacing. Applies only to the
     * step's next dispatch (FR-8.2's "со следующего запуска") — a currently {@code RUNNING}
     * iteration already captured its own prompt text and is unaffected.
     *
     * @param user who edited, {@code at} when — same "кто, когда" shape as {@link GateAnswered}.
     */
    record PromptEdited(RunId runId, String stepId, String content, String user, Instant at) implements EngineCommand {
    }

    /**
     * T20/FR-8.3 trusted harness edit, submitted by the tile inspector while a run is live:
     * {@code relativePath} is project-relative under the harness root (e.g. {@code
     * hooks/tdd-guard.py}), routed through {@link dev.forgeide.core.port.HarnessGuardPort#edit}
     * so a save through the IDE can never itself register as {@code STOPPED(harness-drift)}
     * (SR-8) — only an edit that bypasses this command does.
     */
    record HarnessEdited(RunId runId, String relativePath, String content, String user, Instant at) implements EngineCommand {
    }
}
