package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.project.RiskLevel;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.EscalationAction;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.JudgeVerdict;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.StepRun;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.core.secret.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * T28 "судьи": {@code judge} dispatch (deterministic check + LLM verdict), the FR-4.5 fail/
 * re-iterate loop, and FR-11.3's escalation-answer/override resolution — everything that decides
 * whether a phase's output passed. Runs on {@link PipelineEngine}'s actor thread except where
 * noted ({@link #runJudgeChecks} and its two call sites run on {@code workers}); reaches back
 * into the actor for the shared primitives ({@code audit}/{@code persistAndPublish}/{@code
 * publish}/{@code dispatch}/{@code submit}/{@code markRunning}/{@code logDir}/{@code
 * haltOnEngineError}) exactly as {@link PipelineEngine} itself would.
 */
final class JudgeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(JudgeCoordinator.class);

    private final ScriptRunnerPort scriptRunner;
    private final AgentRuntimePort agentRuntime;
    private final SecretStorePort secretStore;
    private final HarnessGuardPort harnessGuard;
    private final ExecutorService workers;
    private final PhaseDispatcher phaseDispatcher;
    private final PipelineEngine actor;

    JudgeCoordinator(ScriptRunnerPort scriptRunner, AgentRuntimePort agentRuntime, SecretStorePort secretStore,
                      HarnessGuardPort harnessGuard, ExecutorService workers, PhaseDispatcher phaseDispatcher,
                      PipelineEngine actor) {
        this.scriptRunner = scriptRunner;
        this.agentRuntime = agentRuntime;
        this.secretStore = secretStore;
        this.harnessGuard = harnessGuard;
        this.workers = workers;
        this.phaseDispatcher = phaseDispatcher;
        this.actor = actor;
    }

    void dispatchJudge(RunContext ctx, JudgeStep judge) {
        RuntimeBinding llmBinding = null;
        if (judge.llmJudge().isPresent()) {
            String ref = judge.llmJudge().get().runtimeRef();
            Optional<RuntimeBinding> resolved = ctx.project.runtime(ref);
            if (resolved.isEmpty()) {
                actor.haltOnEngineError(ctx, judge.id(), "unknown runtime '" + ref + "'");
                return;
            }
            llmBinding = resolved.get();
        }
        RunId runId = ctx.run.id();
        int iteration = phaseDispatcher.markRunning(ctx, judge.id());
        // SR-7/Т-7 (P): a judge script path that resolves under the project's harness is
        // redirected to the IDE's cache copy of the current baseline, so a compromised prior
        // phase editing e.g. check_coverage.py has no effect on the verdict it grades. Resolved
        // and audited here, on the actor thread, before the worker below even starts — fixed in
        // the audit trail regardless of the check's own PASS/FAIL outcome.
        Optional<List<String>> resolvedCommand = judge.deterministicCheck().map(check ->
                harnessGuard.resolveFromCache(ctx.projectRoot,
                        check.command().stream().map(ctx.resolver::render).toList()));
        if (resolvedCommand.isPresent()) {
            actor.audit(ctx, judge.id(), iteration, "judge.script.resolved",
                    AuditPayloads.judgeScriptResolvedPayload(resolvedCommand.get()));
            actor.persistAndPublish(ctx);
        }
        RuntimeBinding finalLlmBinding = llmBinding;
        Path llmLogDir = phaseDispatcher.logDir(ctx, judge.id(), iteration).resolve("llm");
        workers.execute(() -> {
            try {
                JudgeCheckOutcome outcome = runJudgeChecks(ctx, judge, resolvedCommand, finalLlmBinding, llmLogDir);
                if (outcome.passed()) {
                    actor.submit(new EngineCommand.StepCompleted(runId, judge.id(), iteration, List.of()));
                } else {
                    actor.submit(new EngineCommand.StepFailed(runId, judge.id(), iteration, FailureReason.JUDGE,
                            outcome.detail()));
                }
            } catch (ScriptRunnerException | AgentRuntimeException | RuntimeException ex) {
                actor.submit(new EngineCommand.StepFailed(runId, judge.id(), iteration, FailureReason.JUDGE,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    record JudgeCheckOutcome(boolean passed, String detail) {
    }

    /**
     * The actual "run the check(s) and decide pass/fail" body a judge dispatch performs —
     * factored out so {@link #dispatchJudge} and T21/FR-8.4's {@code
     * DryRunAndPreviewCoordinator#handleJudgeDryRunRequested} execute the identical logic (script
     * command, LLM prompt build, exit-code/verdict combination rules) instead of two copies that
     * could silently drift apart. Runs entirely off the actor thread (both call sites invoke it
     * from inside {@code workers.execute}); reads only the immutable/effectively-final parts of
     * {@code ctx} a normal judge dispatch already reads from a worker thread (resolver,
     * projectRoot, promptSnapshots).
     */
    JudgeCheckOutcome runJudgeChecks(RunContext ctx, JudgeStep judge, Optional<List<String>> resolvedCommand,
                                      RuntimeBinding llmBinding, Path llmLogDir)
            throws ScriptRunnerException, AgentRuntimeException {
        boolean passed = true;
        StringBuilder detail = new StringBuilder();
        if (judge.deterministicCheck().isPresent()) {
            ScriptStep check = judge.deterministicCheck().get();
            List<String> command = resolvedCommand.orElseThrow();
            ScriptResult result = scriptRunner.run(
                    new ScriptInvocation(ctx.projectRoot, command, check.timeout(), Map.of()));
            passed = result.exitCode() == 0;
            detail.append("check exit ").append(result.exitCode());
            // The exit code alone gives the agent nothing to act on for the next iteration's
            // accumulated_errors block (FR-4.5) — carry the check's own diagnostic output too,
            // since that is what actually names the problem.
            String output = !result.stderr().isBlank() ? result.stderr() : result.stdout();
            if (!output.isBlank()) {
                // T27/SD §6.2: this text becomes JudgeVerdict.detail (run.json), the
                // judge.verdict audit payload, and — on FAIL — accumulated_errors folded into
                // the target step's next prompt (meta.json); masking it here, before any of
                // those trusted writes, is the single choke point that covers all three. The
                // check script runs against the target phase's own artifacts, so a secret it
                // prints is one the target step's env_scope handed out.
                Collection<String> targetSecrets =
                        secretStore.resolve(envScopeOf(ctx.stepDefs.get(judge.targetStepId()))).values();
                detail.append(": ").append(SecretMasker.mask(output.strip(), targetSecrets));
            }
        }
        if (judge.llmJudge().isPresent()) {
            AgentStep llm = judge.llmJudge().get();
            String templateKey = ctx.templateKeyOf.getOrDefault(judge.id(), judge.id()) + ".llm";
            String raw = ctx.promptSnapshots.getOrDefault(templateKey, "");
            AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, ctx.resolver.render(raw),
                    llm.budget().wallClock(), llm.budget().tokens(),
                    llm.budget().outputMb() * 1024L * 1024L, llmLogDir, llmBinding,
                    secretStore.resolve(llm.envScope()));
            AgentResult result = agentRuntime.execute(invocation, event -> { });
            boolean llmPassed = result.finalJson().map(JudgeCoordinator::isLlmPass).orElse(false);
            if (!detail.isEmpty()) {
                detail.append("; ");
            }
            detail.append("llm=").append(llmPassed);
            if (judge.deterministicCheck().isEmpty()) {
                passed = llmPassed;
            }
        }
        if (detail.isEmpty()) {
            detail.append("no checks configured");
        }
        return new JudgeCheckOutcome(passed, detail.toString());
    }

    /** {@code env_scope} of a step definition that has one, empty for the ones that don't
     * (T27: what {@link #runJudgeChecks} masks a deterministic check's output against). */
    static List<String> envScopeOf(StepDefinition def) {
        return switch (def) {
            case AgentStep a -> a.envScope();
            case OutwardStep o -> o.envScope();
            case null, default -> List.of();
        };
    }

    private static boolean isLlmPass(JsonNode finalJson) {
        JsonNode verdict = finalJson.get("verdict");
        return verdict != null && verdict.isTextual() && "pass".equalsIgnoreCase(verdict.asText());
    }

    void handleJudgeOutcome(RunContext ctx, JudgeStep judge, StepRun judgeRun, boolean passed, String detail) {
        judgeRun.recordVerdict(new JudgeVerdict(judgeRun.iteration(), Optional.empty(), passed, detail));
        String targetId = judge.targetStepId();
        StepRun targetRun = ctx.run.step(targetId);
        actor.audit(ctx, judge.id(), judgeRun.iteration(), "judge.verdict",
                AuditPayloads.verdictPayload(targetId, passed, detail));

        if (passed) {
            ctx.accumulatedErrors.remove(targetId);
            judgeRun.transitionTo(StepStatus.PASSED);
            actor.persistAndPublish(ctx);
            return;
        }

        ctx.accumulatedErrors.computeIfAbsent(targetId, k -> new ArrayList<>()).add(detail);
        if (judgeRun.iteration() < judge.failPolicy().maxIterations()) {
            targetRun.transitionTo(StepStatus.READY);
            actor.persistAndPublish(ctx);
            ctx.resetQuestionRounds(targetId);
            actor.dispatch(ctx, ctx.stepDefs.get(targetId));
            judgeRun.transitionTo(StepStatus.PENDING);
            actor.persistAndPublish(ctx);
        } else {
            judgeRun.transitionTo(StepStatus.WAITING_GATE);
            String question = "Judge exhausted " + judge.failPolicy().maxIterations() + " iteration(s) for step '"
                    + targetId + "'. Choose how to proceed.";
            List<String> options = Arrays.stream(EscalationAction.values()).map(EscalationAction::token).toList();
            actor.audit(ctx, judge.id(), judgeRun.iteration(), "gate.requested",
                    AuditPayloads.gateRequestedPayload(question, options, targetId));
            actor.persistAndPublish(ctx);
            List<String> errorsHistory = List.copyOf(ctx.accumulatedErrors.getOrDefault(targetId, List.of()));
            actor.publish(new EngineEvent.GateRequest(ctx.run.id(), judge.id(), question, options,
                    escalationArtifacts(ctx, targetId), RiskLevel.R1, errorsHistory));
        }
    }

    /** Real artifacts (not glob-scoped) an escalation's target step declared, for the escalation
     * dialog's diff view (FR-5.2/FR-11.3: real data from disk, not the model's own word). */
    private List<Path> escalationArtifacts(RunContext ctx, String targetId) {
        StepDefinition target = ctx.stepDefs.get(targetId);
        if (target instanceof AgentStep agent) {
            return agent.expectedArtifacts().stream().map(ctx.projectRoot::resolve).toList();
        }
        return List.of();
    }

    /**
     * FR-11.3 escalation resolution — the same {@code GateAnswered} command a real gate answers
     * with, dispatched over {@link EscalationAction} instead of the gate's own free-form options.
     * Returns {@code false} for an answer the engine refuses (unknown token, or a mandatory
     * {@code detail} missing/blank) so the caller can skip the generic {@code advance} — the
     * step is left exactly as it was, still {@code WAITING_GATE}.
     */
    boolean handleEscalationAnswer(RunContext ctx, JudgeStep judge, StepRun sr, EngineCommand.GateAnswered cmd) {
        Optional<EscalationAction> action = EscalationAction.fromToken(cmd.answer());
        if (action.isEmpty()) {
            log.warn("unknown escalation answer '{}' for judge {}", cmd.answer(), cmd.stepId());
            return false;
        }
        String targetId = judge.targetStepId();
        switch (action.get()) {
            case RETRY -> {
                actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
                retryEscalationTarget(ctx, judge, sr);
            }
            case EDIT_PROMPT -> {
                String edited = cmd.detail().orElse("");
                if (edited.isBlank()) {
                    log.warn("edit_prompt escalation for {} rejected: replacement prompt is blank", cmd.stepId());
                    return false;
                }
                ctx.promptOverrides.put(targetId, edited);
                actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
                retryEscalationTarget(ctx, judge, sr);
            }
            case RESET_CHAIN -> {
                ctx.accumulatedErrors.remove(targetId);
                sr.resetIteration();
                actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
                retryEscalationTarget(ctx, judge, sr);
            }
            case CANCEL -> {
                actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
                sr.markFailed(FailureReason.JUDGE);
                actor.persistAndPublish(ctx);
            }
            case OVERRIDE -> {
                String reason = cmd.detail().orElse("");
                if (reason.isBlank()) {
                    log.warn("override for {} rejected: reason is mandatory (FR-11.3/Т-17)", cmd.stepId());
                    return false;
                }
                actor.audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", AuditPayloads.gateAnsweredPayload(cmd));
                actor.audit(ctx, targetId, ctx.run.step(targetId).iteration(), "judge.overridden",
                        AuditPayloads.overridePayload(judge.id(), reason));
                ctx.accumulatedErrors.remove(targetId);
                ctx.run.step(targetId).transitionTo(StepStatus.PASSED);
                sr.transitionTo(StepStatus.PASSED);
                actor.persistAndPublish(ctx);
            }
        }
        return true;
    }

    /** Shared continuation for the three escalation actions that give the target step one more
     * run ({@code retry}, {@code edit_prompt}, {@code reset_chain}) — identical to the plain
     * manual-retry flow, just reached from the escalation dialog instead of a FAILED tile. */
    private void retryEscalationTarget(RunContext ctx, JudgeStep judge, StepRun escalationRun) {
        StepRun targetRun = ctx.run.step(judge.targetStepId());
        targetRun.transitionTo(StepStatus.READY);
        actor.persistAndPublish(ctx);
        ctx.resetQuestionRounds(judge.targetStepId());
        actor.dispatch(ctx, ctx.stepDefs.get(judge.targetStepId()));
        escalationRun.transitionTo(StepStatus.PENDING);
        actor.persistAndPublish(ctx);
    }
}
