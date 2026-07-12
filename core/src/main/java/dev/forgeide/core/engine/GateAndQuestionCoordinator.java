package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.project.RiskLevel;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.QuestionEscalationAction;
import dev.forgeide.core.run.StepRun;
import dev.forgeide.core.run.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T28 "гейты и вопросы": plain {@link GateStep} confirmation, {@code pending_questions} rounds
 * and their FR-10.5 round-limit escalation, and routing a {@code GateAnswered} command to
 * whichever of the three things can be {@code WAITING_GATE} (a real gate, a judge escalation via
 * {@link JudgeCoordinator}, or an exhausted question-round escalation). Runs entirely on the
 * actor thread, same as {@link PipelineEngine} itself.
 */
final class GateAndQuestionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(GateAndQuestionCoordinator.class);

    /** FR-10.5: at most this many {@code pending_questions} rounds per phase attempt before the
     * round-limit escalation dialog takes over (Т-15: "изматывание человека вопросами"). */
    static final int QUESTION_ROUND_LIMIT = 2;

    private final JudgeCoordinator judgeCoordinator;
    private final PipelineEngine actor;

    GateAndQuestionCoordinator(JudgeCoordinator judgeCoordinator, PipelineEngine actor) {
        this.judgeCoordinator = judgeCoordinator;
        this.actor = actor;
    }

    void dispatchGate(RunContext ctx, GateStep gate) {
        StepRun sr = ctx.run.step(gate.id());
        sr.transitionTo(StepStatus.WAITING_GATE);
        actor.audit(ctx, gate.id(), sr.iteration(), "gate.requested",
                AuditPayloads.gateRequestedPayload(gate.question(), gate.options(), gate.id()));
        actor.persistAndPublish(ctx);
        List<Path> artifacts = gate.showArtifacts().stream().map(ctx.projectRoot::resolve).toList();
        actor.publish(new EngineEvent.GateRequest(ctx.run.id(), gate.id(), gate.question(), gate.options(), artifacts,
                gate.risk(), List.of()));
    }

    /** FR-10.5 / Т-15: a 3rd round in the same phase attempt does not ask again — it hands the
     * step to the shared T12 escalation dialog instead, exactly like a judge's exhausted
     * {@code fail_policy} (FR-11.3's "shared infrastructure"), so a model cannot loop a human
     * into exhaustion by endlessly asking one more question. */
    void escalateQuestionRounds(RunContext ctx, String stepId, StepRun sr, List<PendingQuestion> questions) {
        ctx.questionEscalations.add(stepId);
        sr.transitionTo(StepStatus.WAITING_GATE);
        String question = "Step '" + stepId + "' asked a " + (QUESTION_ROUND_LIMIT + 1)
                + "rd round of questions — the limit is " + QUESTION_ROUND_LIMIT
                + " per attempt (FR-10.5). Choose how to proceed.";
        List<String> options = Arrays.stream(QuestionEscalationAction.values())
                .map(QuestionEscalationAction::token).toList();
        ObjectNode payload = AuditPayloads.questionAskedPayload(questions);
        ArrayNode optionsNode = payload.putArray("options");
        options.forEach(optionsNode::add);
        actor.audit(ctx, stepId, sr.iteration(), "question.escalated", payload);
        actor.persistAndPublish(ctx);
        List<String> history = List.copyOf(ctx.questionRoundHistory.getOrDefault(stepId, List.of()));
        actor.publish(new EngineEvent.GateRequest(ctx.run.id(), stepId, question, options, List.of(), RiskLevel.R1,
                history));
    }

    void handleGateAnswered(RunContext ctx, EngineCommand.GateAnswered cmd) {
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        if (def == null || !ctx.run.hasStep(cmd.stepId())) {
            log.warn("gate answered for unknown step {}", cmd.stepId());
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.WAITING_GATE) {
            log.warn("gate answered for step {} not awaiting a gate (status {})", cmd.stepId(), sr.status());
            return;
        }

        if (def instanceof GateStep gate) {
            if (!gate.options().contains(cmd.answer())) {
                log.warn("answer '{}' is not one of {} for gate {}", cmd.answer(), gate.options(), cmd.stepId());
                return;
            }
            // FR-5.3: an R2-risk gate cannot be confirmed without the diff-ack checkbox — checked
            // here too, not just by the dialog disabling its own buttons (SD §4: the UI is
            // untrusted-adjacent, the engine has the final say).
            if (gate.risk() == RiskLevel.R2 && !cmd.diffAcked()) {
                log.warn("gate {} (risk R2) rejected: diff-ack checkbox not confirmed (FR-5.3)", cmd.stepId());
                return;
            }
            ctx.gateAnswers.put(cmd.stepId(), cmd.answer());
            sr.transitionTo(StepStatus.PASSED);
            actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
            actor.persistAndPublish(ctx);
        } else if (def instanceof JudgeStep judge) {
            if (!judgeCoordinator.handleEscalationAnswer(ctx, judge, sr, cmd)) {
                return;
            }
        } else if (ctx.questionEscalations.contains(cmd.stepId())) {
            if (!handleQuestionEscalationAnswer(ctx, sr, cmd)) {
                return;
            }
        } else {
            log.warn("gate answered for a step that is neither a gate nor an escalated judge: {}", cmd.stepId());
            return;
        }
        actor.advance(ctx);
    }

    /** FR-10.5 round-limit escalation resolution. {@code split_step}/{@code cancel} both end the
     * phase attempt as {@code FAILED(questions)} (Т-15: "эскалация как FAIL") — a human can still
     * manually retry (T25's "Повторить с новым промптом" composes exactly that {@code cancel}
     * with a {@code PromptEdited}/{@code RetryStep} pair, no new command needed); {@code
     * open_prompt} never legitimately reaches here (see {@link QuestionEscalationAction}'s
     * javadoc — the UI dismisses and opens the editor locally instead of answering the gate). */
    private boolean handleQuestionEscalationAnswer(RunContext ctx, StepRun sr, EngineCommand.GateAnswered cmd) {
        Optional<QuestionEscalationAction> action = QuestionEscalationAction.fromToken(cmd.answer());
        if (action.isEmpty()) {
            log.warn("unknown question-escalation answer '{}' for step {}", cmd.answer(), cmd.stepId());
            return false;
        }
        if (action.get() == QuestionEscalationAction.OPEN_PROMPT) {
            log.warn("open_prompt question-escalation answer reached the engine for step {} — "
                    + "the UI never submits this as a gate answer (T25: it dismisses and opens the "
                    + "editor instead), refusing defensively", cmd.stepId());
            return false;
        }
        actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
        ctx.questionEscalations.remove(cmd.stepId());
        ctx.questionRounds.remove(cmd.stepId());
        ctx.questionRoundHistory.remove(cmd.stepId());
        sr.markFailed(FailureReason.QUESTIONS);
        actor.persistAndPublish(ctx);
        return true;
    }

    void handleQuestionsAnswered(RunContext ctx, EngineCommand.QuestionsAnswered cmd) {
        if (!ctx.run.hasStep(cmd.stepId())) {
            log.warn("questions answered for unknown step {}", cmd.stepId());
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.WAITING_INPUT) {
            log.warn("questions answered for step {} not awaiting input (status {})", cmd.stepId(), sr.status());
            return;
        }
        ctx.questionRoundHistory.computeIfAbsent(cmd.stepId(), k -> new ArrayList<>())
                .add(questionRoundSummary(sr.pendingQuestions(), cmd.answers()));
        ctx.lastAnswers.put(cmd.stepId(), cmd.answers());
        sr.transitionTo(StepStatus.READY);
        actor.audit(ctx, cmd.stepId(), sr.iteration(), "question.answered", AuditPayloads.questionAnsweredPayload(cmd));
        actor.persistAndPublish(ctx);
        actor.dispatch(ctx, ctx.stepDefs.get(cmd.stepId()));
        actor.advance(ctx);
    }

    /** One line per round for the round-limit escalation dialog's history tab. */
    private static String questionRoundSummary(List<PendingQuestion> questions, Map<String, String> answers) {
        StringBuilder sb = new StringBuilder();
        for (PendingQuestion q : questions) {
            sb.append(q.id()).append(": ").append(q.text()).append(" -> ")
                    .append(answers.getOrDefault(q.id(), "(no answer)")).append('\n');
        }
        return sb.toString();
    }
}
