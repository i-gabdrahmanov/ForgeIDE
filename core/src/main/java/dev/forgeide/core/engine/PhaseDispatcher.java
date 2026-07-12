package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.ProcessSweepPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunLogLayout;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepRun;
import dev.forgeide.core.run.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * T28: dispatches {@code script}/{@code agent}/{@code branch}/{@code per_task_loop} steps — the
 * physical "run this ready step" work left after judges, outward, gates/questions and dry-run/
 * preview were split into their own domains — plus the T20 trusted prompt/harness-edit paths and
 * SR-8's harness-drift stop/resume, which are dispatch-adjacent (a drift check gates every agent
 * phase; a prompt edit only matters to a step's *next* dispatch). Reaches back into {@link
 * PipelineEngine} for the shared actor primitives ({@code markRunning}/{@code logDir}/{@code
 * appendContextBlocks} are actor-wide, used by {@link JudgeCoordinator} and {@link
 * OutwardCoordinator} too, so they stay there rather than move here).
 */
final class PhaseDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PhaseDispatcher.class);

    private final AgentRuntimePort agentRuntime;
    private final ScriptRunnerPort scriptRunner;
    private final ManifestProjectorPort manifestProjector;
    private final ScopeDiffPort scopeDiff;
    private final SecretStorePort secretStore;
    private final HarnessGuardPort harnessGuard;
    private final ProcessSweepPort processSweep;
    private final ExecutorService workers;
    private final PipelineEngine actor;

    PhaseDispatcher(AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner, ManifestProjectorPort manifestProjector,
                     ScopeDiffPort scopeDiff, SecretStorePort secretStore, HarnessGuardPort harnessGuard,
                     ProcessSweepPort processSweep, ExecutorService workers, PipelineEngine actor) {
        this.agentRuntime = agentRuntime;
        this.scriptRunner = scriptRunner;
        this.manifestProjector = manifestProjector;
        this.scopeDiff = scopeDiff;
        this.secretStore = secretStore;
        this.harnessGuard = harnessGuard;
        this.processSweep = processSweep;
        this.workers = workers;
        this.actor = actor;
    }

    int markRunning(RunContext ctx, String stepId) {
        StepRun sr = ctx.run.step(stepId);
        sr.transitionTo(StepStatus.RUNNING);
        sr.startIteration();
        actor.audit(ctx, stepId, sr.iteration(), "step.running", AuditPayloads.empty());
        actor.persistAndPublish(ctx);
        return sr.iteration();
    }

    /** {@code ground/ai-logs/<feature>/iter-NN/<step>/} (SD §6.2). */
    Path logDir(RunContext ctx, String stepId, int iteration) {
        return RunLogLayout.stepLogDir(ctx.projectRoot, ctx.run.featureSlug(), stepId, iteration);
    }

    String appendContextBlocks(RunContext ctx, String stepId, String rendered) {
        StringBuilder sb = new StringBuilder(rendered);
        List<String> errors = ctx.accumulatedErrors.get(stepId);
        if (errors != null && !errors.isEmpty()) {
            sb.append("\n\n## accumulated_errors\n");
            errors.forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        Map<String, String> answers = ctx.lastAnswers.get(stepId);
        if (answers != null && !answers.isEmpty()) {
            sb.append("\n\n## answers\n");
            answers.forEach((q, a) -> sb.append(q).append(": ").append(a).append('\n'));
        }
        return sb.toString();
    }

    void dispatchScript(RunContext ctx, ScriptStep step) {
        RunId runId = ctx.run.id();
        int iteration = markRunning(ctx, step.id());
        workers.execute(() -> {
            try {
                List<String> command = step.command().stream().map(ctx.resolver::render).toList();
                ScriptInvocation inv = new ScriptInvocation(ctx.projectRoot, command, step.timeout(), Map.of());
                ScriptResult result = scriptRunner.run(inv);
                if (result.exitCode() == 0) {
                    actor.submit(new EngineCommand.StepCompleted(runId, step.id(), iteration, List.of()));
                } else {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.SCRIPT,
                            "exit " + result.exitCode() + ": " + excerpt(result.stderr(), result.stdout())));
                }
            } catch (ScriptRunnerException | RuntimeException ex) {
                actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.SCRIPT,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    void dispatchAgent(RunContext ctx, AgentStep step) {
        Optional<RuntimeBinding> binding = ctx.project.runtime(step.runtimeRef());
        if (binding.isEmpty()) {
            actor.haltOnEngineError(ctx, step.id(), "unknown runtime '" + step.runtimeRef() + "'");
            return;
        }
        // SR-8: the project's harness hash-manifest is checked right before every agent phase,
        // before the step even turns RUNNING — a prior phase editing e.g. tdd-guard.py must not
        // let a second untrusted phase start before a human has seen the diff.
        Optional<HarnessGuardPort.Drift> drift = harnessGuard.checkDrift(ctx.projectRoot);
        if (drift.isPresent()) {
            haltOnHarnessDrift(ctx, step.id(), drift.get());
            return;
        }
        RunId runId = ctx.run.id();
        int iteration = markRunning(ctx, step.id());
        // SD §4/T15: projected on the actor thread, before the phase starts — RunContext.run is
        // never touched off this thread, so the worker below only ever sees this immutable
        // snapshot, not the live mutable run.
        RunSnapshot prePhaseSnapshot = ctx.run.snapshot();
        String expectedManifestHash = manifestProjector.project(ctx.projectRoot, ctx.pipelineId,
                ctx.run.featureSlug(), prePhaseSnapshot);
        workers.execute(() -> {
            try {
                String templateKey = ctx.templateKeyOf.getOrDefault(step.id(), step.id());
                String raw = ctx.rawPromptForDispatch(step.id(), templateKey);
                // FR-11.3 "edit_prompt" escalation action: a one-shot human replacement for this
                // single attempt, consumed here (never by {@link RunContext#rawPromptForDispatch}
                // itself, which a T21 preview also calls and must leave the override untouched)
                // rather than left to leak into later iterations.
                ctx.promptOverrides.remove(step.id());
                if (raw == null) {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.ARTIFACTS,
                            "no prompt snapshot for " + templateKey));
                    return;
                }
                String prompt = appendContextBlocks(ctx, step.id(), ctx.resolver.render(raw));
                Path logDir = logDir(ctx, step.id(), iteration);
                // SR-6/Т-13: taken right before the process starts, as close to the phase's real
                // start as the pre-phase manifest projection above is to its own start.
                ScopeDiffPort.Snapshot prePhaseScope = scopeDiff.snapshot(ctx.projectRoot);
                Map<String, String> env = secretStore.resolve(step.envScope());
                AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, prompt,
                        step.budget().wallClock(), step.budget().tokens(),
                        step.budget().outputMb() * 1024L * 1024L, logDir, binding.get(), env);
                AgentResult result = agentRuntime.execute(invocation, event -> { });

                // SR-9/Т-9: swept unconditionally, right after the phase, regardless of its own
                // outcome — an escapee that dodged the phase's own process-group kill by
                // nohup/setsid'ing out of it is an incident to record, not a reason to also fail
                // the step (the step's own PASS/FAIL is decided entirely by the checks below).
                List<Long> orphans = processSweep.sweepOrphans(ctx.projectRoot);
                if (!orphans.isEmpty()) {
                    actor.submit(new EngineCommand.OrphanProcessesSwept(runId, step.id(), iteration, orphans));
                }

                // SR-6/Т-13: checked right after the phase, ahead of the tamper check — a write
                // outside allowed_write (or a HEAD that moved, e.g. a local commit/reset) is a
                // security incident whether or not the phase's own contract otherwise looks fine.
                List<String> scopeViolations = scopeDiff.violations(ctx.projectRoot, prePhaseScope, step.allowedWrite());
                if (!scopeViolations.isEmpty()) {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.SCOPE,
                            "write(s) outside allowed_write: " + String.join(", ", scopeViolations)));
                    return;
                }

                // SR-2/Т-1: tamper-check right after the phase, before any other verdict on the
                // result — a control-plane write outside state-write-guard is a security incident
                // worth catching even when the phase's own artifacts/budget would otherwise pass.
                Optional<String> tamperDiff = manifestProjector.verifyAndRestore(ctx.projectRoot, ctx.pipelineId,
                        ctx.run.featureSlug(), prePhaseSnapshot, expectedManifestHash);
                if (tamperDiff.isPresent()) {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.TAMPERED,
                            tamperDiff.get()));
                    return;
                }
                manifestProjector.readOrigin(ctx.projectRoot, ctx.pipelineId, ctx.run.featureSlug(), step.id())
                        .ifPresent(origin -> actor.submit(
                                new EngineCommand.EvidenceObserved(runId, step.id(), iteration, origin)));

                if (result.usage().total() > step.budget().tokens()) {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.BUDGET,
                            "token usage " + result.usage().total() + " exceeded budget " + step.budget().tokens()));
                    return;
                }
                if (result.finalJson().isEmpty()) {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.STREAM,
                            "no result event"));
                    return;
                }
                Optional<String> artifactError = ArtifactValidation.validate(ctx.projectRoot, step.expectedArtifacts());
                if (artifactError.isPresent()) {
                    actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.ARTIFACTS,
                            artifactError.get()));
                    return;
                }
                List<PendingQuestion> questions = PendingQuestions.parse(result.finalJson().get());
                actor.submit(new EngineCommand.StepCompleted(runId, step.id(), iteration, questions));
            } catch (AgentRuntimeException | RuntimeException ex) {
                actor.submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.STREAM,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    void dispatchBranch(RunContext ctx, BranchStep branch) {
        StepRun sr = ctx.run.step(branch.id());
        String answer = branch.dependsOn().stream()
                .filter(ctx.gateAnswers::containsKey)
                .map(ctx.gateAnswers::get)
                .findFirst()
                .orElse(null);
        if (answer == null) {
            actor.haltOnEngineError(ctx, branch.id(),
                    "branch has no recorded gate answer among its depends_on");
            return;
        }
        String chosen = branch.routes().get(answer);
        if (chosen == null) {
            actor.haltOnEngineError(ctx, branch.id(), "no route for answer '" + answer + "'");
            return;
        }
        for (String target : new LinkedHashSet<>(branch.routes().values())) {
            if (!target.equals(chosen) && ctx.run.hasStep(target) && ctx.run.step(target).status() == StepStatus.PENDING) {
                ctx.run.step(target).transitionTo(StepStatus.SKIPPED);
                actor.persistAndPublish(ctx);
            }
        }
        // A chosen target that is already terminal (a loop back to an earlier, already-PASSED
        // step) is not re-armed here — see TemplateExpansion/BranchStep docs: T06 only routes
        // forward, it does not reset the whole cycle behind the branch.
        sr.transitionTo(StepStatus.PASSED);
        actor.persistAndPublish(ctx);
    }

    void dispatchPerTaskLoop(RunContext ctx, PerTaskLoop loop) {
        StepRun sr = ctx.run.step(loop.id());
        sr.transitionTo(StepStatus.RUNNING);
        sr.startIteration();
        actor.persistAndPublish(ctx);
        try {
            List<String> taskIds = ResumeReplay.readTaskIds(ctx.projectRoot.resolve(loop.taskPlanJson()));
            for (String taskId : taskIds) {
                List<StepDefinition> expanded = TemplateExpansion.expandForTask(loop, taskId);
                for (int i = 0; i < expanded.size(); i++) {
                    StepDefinition instance = expanded.get(i);
                    ctx.stepDefs.put(instance.id(), instance);
                    ctx.run.addStep(instance.id());
                    ctx.templateKeyOf.put(instance.id(), loop.id() + "/" + loop.template().get(i).id());
                }
            }
            sr.transitionTo(StepStatus.PASSED);
            actor.persistAndPublish(ctx);
        } catch (IOException | RuntimeException ex) {
            log.error("per_task_loop {} failed to expand", loop.id(), ex);
            sr.markFailed(FailureReason.ARTIFACTS);
            actor.persistAndPublish(ctx);
        }
    }

    private static String excerpt(String stderr, String stdout) {
        String text = stderr != null && !stderr.isBlank() ? stderr : stdout;
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /**
     * FR-3.5 traceability row T-12: a retry (auto or manual) still executes with the prompt text
     * snapshotted at run start — determinism within a run is non-negotiable — but if the file on
     * disk has since changed, that is worth a loud warning in the timeline rather than a silent
     * "why didn't my edit take effect".
     */
    void warnIfPromptDrifted(RunContext ctx, StepDefinition def) {
        if (!(def instanceof AgentStep step)) {
            return;
        }
        String templateKey = ctx.templateKeyOf.getOrDefault(step.id(), step.id());
        String snapshot = ctx.promptSnapshots.get(templateKey);
        if (snapshot == null) {
            return;
        }
        String current;
        try {
            current = RunLifecycle.readPromptFile(ctx.projectRoot, step.promptTemplate());
        } catch (RuntimeException ex) {
            return;
        }
        if (!snapshot.equals(current)) {
            actor.audit(ctx, step.id(), 0, "prompt.drift",
                    AuditPayloads.promptDriftPayload(step.promptTemplate().toString(), snapshot, current));
        }
    }

    /**
     * T20/FR-8.2 trusted-path counterpart to {@link #warnIfPromptDrifted}: unlike a drift
     * warning, this one actually takes effect — it writes {@code cmd.content()} to the prompt
     * file and updates {@link RunContext#promptSnapshots} under the same {@code templateKey}
     * {@link #warnIfPromptDrifted}/dispatch already use, so the step's *next* dispatch (re-run,
     * judge retry, manual retry) picks it up. A currently {@code RUNNING} iteration already
     * captured its own prompt text at dispatch time and is left alone — that is the entire
     * "next step run only" guarantee, no extra bookkeeping needed.
     */
    void handlePromptEdited(RunContext ctx, EngineCommand.PromptEdited cmd) {
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        Path promptTemplate;
        String templateKey;
        if (def instanceof AgentStep agent) {
            promptTemplate = agent.promptTemplate();
            templateKey = ctx.templateKeyOf.getOrDefault(agent.id(), agent.id());
        } else if (def instanceof JudgeStep judge && judge.llmJudge().isPresent()) {
            AgentStep llm = judge.llmJudge().get();
            promptTemplate = llm.promptTemplate();
            templateKey = llm.id();
        } else {
            log.warn("prompt edit for {} rejected: not an agent/llm-judge step", cmd.stepId());
            return;
        }
        String previous = ctx.promptSnapshots.get(templateKey);
        Path target = ctx.projectRoot.resolve(promptTemplate);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, cmd.content());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write prompt template: " + promptTemplate, e);
        }
        ctx.promptSnapshots.put(templateKey, cmd.content());
        int iteration = ctx.run.hasStep(cmd.stepId()) ? ctx.run.step(cmd.stepId()).iteration() : 0;
        actor.audit(ctx, cmd.stepId(), iteration, "prompt.edited",
                AuditPayloads.promptEditedPayload(promptTemplate.toString(), previous == null ? "" : previous, cmd));
    }

    /**
     * T20/FR-8.3: the tile inspector's trusted path for a judge/hook script — routes through
     * {@link HarnessGuardPort#edit} (T18) so the save itself becomes the new baseline instead of
     * ever registering as {@code STOPPED(harness-drift)} (SR-8); an edit that bypasses this
     * command remains drift, caught the ordinary way before the next agent phase.
     */
    void handleHarnessEdited(RunContext ctx, EngineCommand.HarnessEdited cmd) {
        HarnessGuardPort.HarnessEditResult result =
                harnessGuard.edit(ctx.projectRoot, cmd.relativePath(), cmd.content());
        actor.audit(ctx, null, 0, "harness.edited", AuditPayloads.harnessEditedPayload(result, cmd));
    }

    /**
     * SR-8's GWT: stops the whole run — not just the step about to dispatch — the moment the
     * project's harness hash-manifest no longer matches its last deployed/accepted baseline. The
     * step itself is left exactly where {@code advance} put it ({@code READY}, never {@code
     * RUNNING}) so {@link #handleHarnessDriftResolved} can simply re-dispatch it once a human has
     * accepted or rolled back the diff — no different from how it would have started had drift
     * never tripped.
     */
    private void haltOnHarnessDrift(RunContext ctx, String stepId, HarnessGuardPort.Drift drift) {
        log.warn("run {} stopped: harness drift before step {}", ctx.run.id(), stepId);
        ctx.harnessDriftStepId = stepId;
        ctx.run.stop(RunHaltReason.HARNESS_DRIFT);
        actor.audit(ctx, stepId, ctx.run.step(stepId).iteration(), "run.stopped",
                AuditPayloads.haltPayload(RunHaltReason.HARNESS_DRIFT.name(), drift.diff()));
        actor.persistAndPublish(ctx);
    }

    void handleHarnessDriftResolved(RunContext ctx, EngineCommand.HarnessDriftResolved cmd) {
        if (ctx.run.status() != RunStatus.STOPPED || ctx.run.haltReason().orElse(null) != RunHaltReason.HARNESS_DRIFT
                || ctx.harnessDriftStepId == null) {
            return;
        }
        String stepId = ctx.harnessDriftStepId;
        int iteration = ctx.run.step(stepId).iteration();
        switch (cmd.action()) {
            case ACCEPT -> {
                harnessGuard.acceptDrift(ctx.projectRoot);
                actor.audit(ctx, stepId, iteration, "harness.drift.accepted",
                        AuditPayloads.harnessDriftResolvedPayload(cmd));
            }
            case ROLLBACK -> {
                List<String> restored = harnessGuard.rollbackDrift(ctx.projectRoot);
                ObjectNode payload = AuditPayloads.harnessDriftResolvedPayload(cmd);
                ArrayNode restoredNode = payload.putArray("restored");
                restored.forEach(restoredNode::add);
                actor.audit(ctx, stepId, iteration, "harness.drift.rolledback", payload);
            }
        }
        ctx.harnessDriftStepId = null;
        ctx.run.resume();
        actor.persistAndPublish(ctx);
        actor.dispatch(ctx, ctx.stepDefs.get(stepId));
    }

    /** T15 scope: records a {@code _origins/<stepId>.json} evidence sighting without touching
     * the step's status — the audit trail gets a pointer to it, but the PASSED/FAILED decision
     * stays exactly what the ordinary dispatch flow already computed ("движок ... переходы
     * делает сам"). Silently ignored for a stale/unknown step, same spirit as a stale {@code
     * StepCompleted}/{@code StepFailed} (not worth halting the run over a race with a slow hook). */
    void handleEvidenceObserved(RunContext ctx, EngineCommand.EvidenceObserved cmd) {
        if (!ctx.run.hasStep(cmd.stepId())) {
            return;
        }
        actor.audit(ctx, cmd.stepId(), cmd.iteration(), "evidence.origin", cmd.payload());
    }

    /** T19/SR-9/Т-9: informational only — an orphan sweep never touches step status, same spirit
     * as {@link #handleEvidenceObserved}. */
    void handleOrphanProcessesSwept(RunContext ctx, EngineCommand.OrphanProcessesSwept cmd) {
        if (!ctx.run.hasStep(cmd.stepId())) {
            return;
        }
        actor.audit(ctx, cmd.stepId(), cmd.iteration(), "incident.orphan_process",
                AuditPayloads.orphanSweptPayload(cmd.pids()));
    }
}
