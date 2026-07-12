package dev.forgeide.core.engine;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.RunLogLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * T28 "dry-run и предпросмотр промпта" (T21/FR-8.4-8.5): running a judge's check(s) against
 * whatever already sits on disk without touching {@code run.json}/step status, and rendering the
 * exact prompt text a step's next dispatch would send. Reuses {@link JudgeCoordinator#runJudgeChecks}
 * so a dry-run can never silently drift from a real judge dispatch.
 */
final class DryRunAndPreviewCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DryRunAndPreviewCoordinator.class);

    private final HarnessGuardPort harnessGuard;
    private final ExecutorService workers;
    private final StateStore stateStore;
    private final JudgeCoordinator judgeCoordinator;
    private final PhaseDispatcher phaseDispatcher;
    private final PipelineEngine actor;

    DryRunAndPreviewCoordinator(HarnessGuardPort harnessGuard, ExecutorService workers, StateStore stateStore,
                                 JudgeCoordinator judgeCoordinator, PhaseDispatcher phaseDispatcher,
                                 PipelineEngine actor) {
        this.harnessGuard = harnessGuard;
        this.workers = workers;
        this.stateStore = stateStore;
        this.judgeCoordinator = judgeCoordinator;
        this.phaseDispatcher = phaseDispatcher;
        this.actor = actor;
    }

    /**
     * T21/FR-8.4 "прогнать судью": runs {@code judgeStepId}'s check(s) against whatever sits on
     * disk at its target step's {@code expected_artifacts} right now — no {@code StepRun}
     * transition, no {@code run.json} write, works no matter the target step's own current
     * status. {@code missing.isPresent()} ("если пути существуют" from the task scope) short-
     * circuits before ever touching the script/LLM runtime, same message shape {@link
     * ArtifactValidation} already gives a real agent-phase artifacts failure.
     */
    void handleJudgeDryRunRequested(RunContext ctx, EngineCommand.JudgeDryRunRequested cmd) {
        StepDefinition def = ctx.stepDefs.get(cmd.judgeStepId());
        if (!(def instanceof JudgeStep judge)) {
            log.warn("dry-run requested for {} which is not a judge step", cmd.judgeStepId());
            return;
        }
        StepDefinition targetDef = ctx.stepDefs.get(judge.targetStepId());
        List<Path> expectedArtifacts = targetDef instanceof AgentStep agentTarget
                ? agentTarget.expectedArtifacts() : List.of();
        int iteration = ctx.run.hasStep(judge.targetStepId()) ? ctx.run.step(judge.targetStepId()).iteration() : 0;
        Optional<String> missing = ArtifactValidation.validate(ctx.projectRoot, expectedArtifacts);
        if (missing.isPresent()) {
            actor.submit(new EngineCommand.JudgeDryRunCompleted(cmd.runId(), cmd.judgeStepId(), cmd.requestId(),
                    iteration, false, missing.get()));
            return;
        }
        RuntimeBinding llmBinding = null;
        if (judge.llmJudge().isPresent()) {
            String ref = judge.llmJudge().get().runtimeRef();
            Optional<RuntimeBinding> resolved = ctx.project.runtime(ref);
            if (resolved.isEmpty()) {
                actor.submit(new EngineCommand.JudgeDryRunCompleted(cmd.runId(), cmd.judgeStepId(), cmd.requestId(),
                        iteration, false, "unknown runtime '" + ref + "'"));
                return;
            }
            llmBinding = resolved.get();
        }
        // SR-7: the same cache-redirected command resolution a real dispatch uses, re-resolved
        // fresh on every dry-run click — a T20 trusted-path script edit (which refreshes the
        // cache) is picked up immediately, no extra invalidation needed.
        Optional<List<String>> resolvedCommand = judge.deterministicCheck().map(check ->
                harnessGuard.resolveFromCache(ctx.projectRoot,
                        check.command().stream().map(ctx.resolver::render).toList()));
        RuntimeBinding finalLlmBinding = llmBinding;
        Path llmLogDir = RunLogLayout.stepLogDir(ctx.projectRoot, ctx.run.featureSlug(), judge.id(), iteration)
                .resolve("dryrun").resolve("llm");
        workers.execute(() -> {
            try {
                JudgeCoordinator.JudgeCheckOutcome outcome =
                        judgeCoordinator.runJudgeChecks(ctx, judge, resolvedCommand, finalLlmBinding, llmLogDir);
                actor.submit(new EngineCommand.JudgeDryRunCompleted(cmd.runId(), cmd.judgeStepId(), cmd.requestId(),
                        iteration, outcome.passed(), outcome.detail()));
            } catch (ScriptRunnerException | AgentRuntimeException | RuntimeException ex) {
                actor.submit(new EngineCommand.JudgeDryRunCompleted(cmd.runId(), cmd.judgeStepId(), cmd.requestId(),
                        iteration, false, String.valueOf(ex.getMessage())));
            }
        });
    }

    /**
     * T21/FR-8.4 completion: deliberately calls {@link StateStore#appendAudit} directly instead
     * of the shared {@code PipelineEngine#audit} helper — that helper also links the entry onto
     * the target {@link dev.forgeide.core.run.StepRun} ({@code StepRun#recordEvent}), which is
     * part of the persisted {@link dev.forgeide.core.run.RunSnapshot}/{@code run.json} (the SoT).
     * A dry-run must leave both untouched (task acceptance: "не меняет run.json и статусы; в
     * аудите только judge.dryrun") — only the append-only audit log, never folded back into
     * run.json, records that it happened.
     */
    void handleJudgeDryRunCompleted(RunContext ctx, EngineCommand.JudgeDryRunCompleted cmd) {
        AuditEvent envelope = new AuditEvent(0, Instant.now(), ctx.run.id(), cmd.judgeStepId(), cmd.iteration(),
                "judge.dryrun", AuditPayloads.judgeDryRunPayload(cmd.judgeStepId(), cmd.passed(), cmd.detail()),
                "", "");
        stateStore.appendAudit(envelope);
        actor.publish(new EngineEvent.JudgeDryRunResult(ctx.run.id(), cmd.judgeStepId(), cmd.requestId(),
                cmd.passed(), cmd.detail()));
    }

    /**
     * T21/FR-8.5: renders exactly the prompt text {@code def}'s next dispatch would send — reuses
     * {@link RunContext#rawPromptForDispatch}, {@link dev.forgeide.core.vars.VariableResolver#render},
     * and {@link PhaseDispatcher#appendContextBlocks} (for an {@code AgentStep}; a judge's {@code
     * llmJudge} skips that last step, same as a real judge dispatch itself does), not a
     * reimplementation of any of them, so a preview can never quietly drift from what a real run
     * sends. Empty for anything else (a {@code ScriptStep}, a judge with no {@code llmJudge}, or
     * a step whose prompt was never snapshotted).
     */
    private Optional<String> renderPromptPreview(RunContext ctx, StepDefinition def) {
        if (def instanceof AgentStep agent) {
            String templateKey = ctx.templateKeyOf.getOrDefault(agent.id(), agent.id());
            String raw = ctx.rawPromptForDispatch(agent.id(), templateKey);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(phaseDispatcher.appendContextBlocks(ctx, agent.id(), ctx.resolver.render(raw)));
        }
        if (def instanceof JudgeStep judge && judge.llmJudge().isPresent()) {
            // Mirrors a real judge dispatch's own LLM-verdict prompt build exactly: no
            // promptOverrides consultation, no accumulated_errors/answers blocks — judges never
            // receive either.
            String templateKey = ctx.templateKeyOf.getOrDefault(judge.id(), judge.id()) + ".llm";
            return Optional.of(ctx.resolver.render(ctx.promptSnapshots.getOrDefault(templateKey, "")));
        }
        return Optional.empty();
    }

    void handlePromptPreviewRequested(RunContext ctx, EngineCommand.PromptPreviewRequested cmd) {
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        if (def == null) {
            log.warn("prompt preview requested for unknown step {}", cmd.stepId());
            return;
        }
        Optional<String> rendered = renderPromptPreview(ctx, def);
        if (rendered.isEmpty()) {
            log.warn("prompt preview for {} rejected: not an agent/llm-judge step, or no prompt snapshot yet",
                    cmd.stepId());
            return;
        }
        actor.publish(new EngineEvent.PromptPreviewReady(ctx.run.id(), cmd.stepId(), cmd.requestId(), rendered.get()));
    }
}
