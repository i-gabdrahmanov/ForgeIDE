package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ProcessSweepPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.AuditRef;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepRun;
import dev.forgeide.core.run.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * The pipeline actor (SD §3, T06): a single mutating thread draining a mailbox, with the
 * actual step work (agent/script/judge calls through the ports) running on {@code workers}
 * virtual threads and reporting back via {@link EngineCommand}. Readiness ({@code depends_on}
 * all {@code PASSED}), the judge fail/re-iterate loop, and {@code per_task_loop} expansion all
 * happen only on the actor thread — that is the entire determinism argument (SD §2).
 *
 * <p>{@link RunContext} (and the {@link PipelineRun} it wraps) is never touched from any other
 * thread; every transition is persisted via {@link StateStore#save} before the corresponding
 * {@link EngineEvent.RunUpdated} is published (FR-3.3).
 */
public final class PipelineEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);

    private final StateStore stateStore;
    private final AgentRuntimePort agentRuntime;
    private final ScriptRunnerPort scriptRunner;
    private final ManifestProjectorPort manifestProjector;
    private final ScopeDiffPort scopeDiff;
    private final SecretStorePort secretStore;
    private final OutwardActionsPort outwardActions;
    private final HarnessGuardPort harnessGuard;
    private final ProcessSweepPort processSweep;
    private final ExecutorService workers;

    // T28: domain collaborators the actor dispatches into — see each class's own javadoc.
    private final PhaseDispatcher phaseDispatcher;
    private final JudgeCoordinator judgeCoordinator;
    private final OutwardCoordinator outwardCoordinator;
    private final GateAndQuestionCoordinator gateAndQuestionCoordinator;
    private final DryRunAndPreviewCoordinator dryRunAndPreview;
    private final RunLifecycle runLifecycle;

    private final BlockingQueue<Runnable> mailbox = new LinkedBlockingQueue<>();
    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<RunId, RunContext> runs = new LinkedHashMap<>();
    private final Map<RunId, RunSnapshot> latestSnapshots = new ConcurrentHashMap<>();
    private final Thread actorThread;
    private volatile boolean running = true;

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner) {
        this(stateStore, agentRuntime, scriptRunner, ManifestProjectorPort.NOOP, Executors.newVirtualThreadPerTaskExecutor());
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, Executors.newVirtualThreadPerTaskExecutor());
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ExecutorService workers) {
        this(stateStore, agentRuntime, scriptRunner, ManifestProjectorPort.NOOP, workers);
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ExecutorService workers) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, ScopeDiffPort.NOOP, SecretStorePort.NOOP, workers);
    }

    /** T16 scope-diff (SR-6) + env-scoping (SR-5), default {@code workers}. */
    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore, OutwardActionsPort.NOOP);
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           ExecutorService workers) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore,
                OutwardActionsPort.NOOP, workers);
    }

    /** T17 outward-actions (SR-4), default {@code workers}. */
    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore, outwardActions,
                HarnessGuardPort.NOOP);
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions, ExecutorService workers) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore, outwardActions,
                HarnessGuardPort.NOOP, workers);
    }

    /** T18 harness-integrity (SR-7/SR-8), default {@code workers}. */
    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions, HarnessGuardPort harnessGuard) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore, outwardActions,
                harnessGuard, ProcessSweepPort.NOOP);
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions, HarnessGuardPort harnessGuard, ExecutorService workers) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore, outwardActions,
                harnessGuard, ProcessSweepPort.NOOP, workers);
    }

    /** T19 orphan-process sweep (SR-9/Т-9), default {@code workers}. */
    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions, HarnessGuardPort harnessGuard, ProcessSweepPort processSweep) {
        this(stateStore, agentRuntime, scriptRunner, manifestProjector, scopeDiff, secretStore, outwardActions,
                harnessGuard, processSweep, Executors.newVirtualThreadPerTaskExecutor());
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions, HarnessGuardPort harnessGuard, ProcessSweepPort processSweep,
                           ExecutorService workers) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.agentRuntime = Objects.requireNonNull(agentRuntime, "agentRuntime");
        this.scriptRunner = Objects.requireNonNull(scriptRunner, "scriptRunner");
        this.manifestProjector = Objects.requireNonNull(manifestProjector, "manifestProjector");
        this.scopeDiff = Objects.requireNonNull(scopeDiff, "scopeDiff");
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.outwardActions = Objects.requireNonNull(outwardActions, "outwardActions");
        this.harnessGuard = Objects.requireNonNull(harnessGuard, "harnessGuard");
        this.processSweep = Objects.requireNonNull(processSweep, "processSweep");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.phaseDispatcher = new PhaseDispatcher(this.agentRuntime, this.scriptRunner, this.manifestProjector,
                this.scopeDiff, this.secretStore, this.harnessGuard, this.processSweep, this.workers, this);
        this.judgeCoordinator = new JudgeCoordinator(this.scriptRunner, this.agentRuntime, this.secretStore,
                this.harnessGuard, this.workers, this.phaseDispatcher, this);
        this.outwardCoordinator = new OutwardCoordinator(this.outwardActions, this.secretStore, this.workers,
                this.phaseDispatcher, this);
        this.gateAndQuestionCoordinator = new GateAndQuestionCoordinator(this.judgeCoordinator, this);
        this.dryRunAndPreview = new DryRunAndPreviewCoordinator(this.harnessGuard, this.workers, this.stateStore,
                this.judgeCoordinator, this.phaseDispatcher, this);
        this.runLifecycle = new RunLifecycle(this.harnessGuard, this.stateStore, this);
        this.actorThread = new Thread(this::runLoop, "forgeide-pipeline-engine");
        this.actorThread.setDaemon(true);
        this.actorThread.start();
    }

    // ---- public API ---------------------------------------------------------------------

    /**
     * Starts a run for {@code featureSlug}. Snapshots {@code definition} and every prompt file
     * it references (FR-3.5) before the first readiness pass. Returns immediately; the run
     * becomes visible via {@link #subscribe} / {@link #snapshot} shortly after.
     */
    public RunId start(ProjectDefinition project, PipelineDefinition definition, String featureSlug) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(featureSlug, "featureSlug");
        RunId runId = RunId.newId();
        enqueue(() -> runLifecycle.bootstrap(runId, project, definition, featureSlug));
        return runId;
    }

    /**
     * Rehydrates a run persisted by an earlier (now dead) process into this engine (SDD FR-3.4) —
     * typically right after {@code StartupRecovery} has turned an abandoned step into a terminal
     * {@code FAILED(interrupted)} a human can retry. A no-op if {@code runId} is already live in
     * this engine. Returns immediately; the run becomes visible via {@link #subscribe} / {@link
     * #snapshot} shortly after, same as {@link #start}.
     */
    public void resume(ProjectDefinition project, PipelineDefinition definition, RunId runId) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(runId, "runId");
        enqueue(() -> runLifecycle.rehydrate(project, definition, runId));
    }

    /** Posts a command from the UI or a step executor into the actor's mailbox. */
    public void submit(EngineCommand command) {
        Objects.requireNonNull(command, "command");
        enqueue(() -> handle(command));
    }

    /** Registers a listener for every published event, across all runs. */
    public void subscribe(Consumer<EngineEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Removes a listener registered via {@link #subscribe} (e.g. when a UI view is disposed). */
    public void unsubscribe(Consumer<EngineEvent> listener) {
        listeners.remove(listener);
    }

    /** Last snapshot published for {@code runId}, if any — safe to call from any thread. */
    public Optional<RunSnapshot> snapshot(RunId runId) {
        return Optional.ofNullable(latestSnapshots.get(runId));
    }

    @Override
    public void close() {
        running = false;
        mailbox.offer(() -> { });
        workers.shutdown();
        actorThread.interrupt();
        try {
            actorThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- actor loop -----------------------------------------------------------------------

    private void enqueue(Runnable task) {
        if (!running) {
            throw new IllegalStateException("engine is closed");
        }
        try {
            mailbox.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while enqueuing", e);
        }
    }

    private void runLoop() {
        while (running) {
            Runnable task;
            try {
                task = mailbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.run();
            } catch (RuntimeException ex) {
                log.error("pipeline engine actor task failed", ex);
            }
        }
    }

    // ---- run registry (T28: RunLifecycle/collaborators register/look up live runs here) ---

    RunContext runFor(RunId runId) {
        return runs.get(runId);
    }

    boolean hasRun(RunId runId) {
        return runs.containsKey(runId);
    }

    void registerRun(RunId runId, RunContext ctx) {
        runs.put(runId, ctx);
    }

    // ---- command handling -------------------------------------------------------------

    private void handle(EngineCommand command) {
        RunContext ctx = runFor(command.runId());
        if (ctx == null) {
            log.warn("command for unknown run {}: {}", command.runId(), command);
            return;
        }
        // FR-11.4: an uncaught exception anywhere in a command handler must not just vanish into
        // the actor-loop's own log-and-continue catch (PipelineEngine#runLoop) — the run it broke
        // has to surface as PAUSED(engine-error) so recovery (FR-3.4) picks it up on restart.
        try {
            switch (command) {
                case EngineCommand.StepCompleted c -> handleStepCompleted(ctx, c);
                case EngineCommand.StepFailed c -> handleStepFailed(ctx, c);
                case EngineCommand.GateAnswered c -> gateAndQuestionCoordinator.handleGateAnswered(ctx, c);
                case EngineCommand.QuestionsAnswered c -> gateAndQuestionCoordinator.handleQuestionsAnswered(ctx, c);
                case EngineCommand.EvidenceObserved c -> phaseDispatcher.handleEvidenceObserved(ctx, c);
                case EngineCommand.OrphanProcessesSwept c -> phaseDispatcher.handleOrphanProcessesSwept(ctx, c);
                case EngineCommand.OutwardCompleted c -> outwardCoordinator.handleOutwardCompleted(ctx, c);
                case EngineCommand.HarnessDriftResolved c -> phaseDispatcher.handleHarnessDriftResolved(ctx, c);
                case EngineCommand.CancelRun c -> handleCancelRun(ctx);
                case EngineCommand.RetryStep c -> handleRetryStep(ctx, c);
                case EngineCommand.PromptEdited c -> phaseDispatcher.handlePromptEdited(ctx, c);
                case EngineCommand.HarnessEdited c -> phaseDispatcher.handleHarnessEdited(ctx, c);
                case EngineCommand.JudgeDryRunRequested c -> dryRunAndPreview.handleJudgeDryRunRequested(ctx, c);
                case EngineCommand.JudgeDryRunCompleted c -> dryRunAndPreview.handleJudgeDryRunCompleted(ctx, c);
                case EngineCommand.PromptPreviewRequested c -> dryRunAndPreview.handlePromptPreviewRequested(ctx, c);
            }
        } catch (RuntimeException ex) {
            log.error("run {} command handling failed for {}", ctx.run.id(), command, ex);
            if (ctx.run.status() == RunStatus.RUNNING) {
                haltOnEngineError(ctx, stepIdOf(command), String.valueOf(ex.getMessage()));
            }
        }
    }

    private static String stepIdOf(EngineCommand command) {
        return switch (command) {
            case EngineCommand.StepCompleted c -> c.stepId();
            case EngineCommand.StepFailed c -> c.stepId();
            case EngineCommand.GateAnswered c -> c.stepId();
            case EngineCommand.QuestionsAnswered c -> c.stepId();
            case EngineCommand.EvidenceObserved c -> c.stepId();
            case EngineCommand.OrphanProcessesSwept c -> c.stepId();
            case EngineCommand.OutwardCompleted c -> c.stepId();
            case EngineCommand.RetryStep c -> c.stepId();
            case EngineCommand.PromptEdited c -> c.stepId();
            case EngineCommand.HarnessDriftResolved c -> null;
            case EngineCommand.HarnessEdited c -> null;
            case EngineCommand.CancelRun c -> null;
            case EngineCommand.JudgeDryRunRequested c -> c.judgeStepId();
            case EngineCommand.JudgeDryRunCompleted c -> c.judgeStepId();
            case EngineCommand.PromptPreviewRequested c -> c.stepId();
        };
    }

    private void handleStepCompleted(RunContext ctx, EngineCommand.StepCompleted cmd) {
        if (ctx.run.status() != RunStatus.RUNNING) {
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.RUNNING || sr.iteration() != cmd.iteration()) {
            log.debug("stale StepCompleted for {} iter {} (current {} iter {})",
                    cmd.stepId(), cmd.iteration(), sr.status(), sr.iteration());
            return;
        }
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        if (def instanceof JudgeStep judge) {
            judgeCoordinator.handleJudgeOutcome(ctx, judge, sr, true, "");
        } else if (!cmd.pendingQuestions().isEmpty()) {
            int round = ctx.questionRounds.merge(cmd.stepId(), 1, Integer::sum);
            if (round > GateAndQuestionCoordinator.QUESTION_ROUND_LIMIT) {
                gateAndQuestionCoordinator.escalateQuestionRounds(ctx, cmd.stepId(), sr, cmd.pendingQuestions());
            } else {
                sr.awaitInput(cmd.pendingQuestions());
                audit(ctx, cmd.stepId(), cmd.iteration(), "question.asked",
                        AuditPayloads.questionAskedPayload(cmd.pendingQuestions()));
                persistAndPublish(ctx);
                publish(new EngineEvent.QuestionsPending(ctx.run.id(), cmd.stepId(), cmd.pendingQuestions()));
            }
        } else {
            sr.transitionTo(StepStatus.PASSED);
            ctx.autoRetryCounts.remove(cmd.stepId());
            audit(ctx, cmd.stepId(), cmd.iteration(), "step.completed", AuditPayloads.empty());
            persistAndPublish(ctx);
        }
        advance(ctx);
    }

    private void handleStepFailed(RunContext ctx, EngineCommand.StepFailed cmd) {
        if (ctx.run.status() != RunStatus.RUNNING) {
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.RUNNING || sr.iteration() != cmd.iteration()) {
            log.debug("stale StepFailed for {} iter {} (current {} iter {})",
                    cmd.stepId(), cmd.iteration(), sr.status(), sr.iteration());
            return;
        }
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        if (def instanceof JudgeStep judge) {
            judgeCoordinator.handleJudgeOutcome(ctx, judge, sr, false, cmd.detail());
            advance(ctx);
            return;
        }
        audit(ctx, cmd.stepId(), cmd.iteration(), "step.failed", AuditPayloads.failedPayload(cmd.reason(), cmd.detail()));
        if (cmd.reason() == FailureReason.TAMPERED) {
            // SR-2/Т-1: a manifest-projection hash mismatch is a security incident, not just an
            // ordinary step failure — the canonical incident.tamper event (SDD §5.3) carries the
            // same reason/diff as step.failed above, just filed under its own auditable type so
            // it is easy to find without scanning every step.failed for reason=TAMPERED.
            audit(ctx, cmd.stepId(), cmd.iteration(), "incident.tamper", AuditPayloads.failedPayload(cmd.reason(), cmd.detail()));
        } else if (cmd.reason() == FailureReason.SCOPE) {
            // SR-6/Т-13: same reasoning as incident.tamper above — a write outside allowed_write
            // (or a HEAD that moved) is a security incident, filed under its own auditable type
            // with the violating paths in detail, ready for the incident dialog's "roll back
            // excess" action.
            audit(ctx, cmd.stepId(), cmd.iteration(), "incident.scope", AuditPayloads.failedPayload(cmd.reason(), cmd.detail()));
        }

        // FR-11.2: auto-retry only the classes that are safe to blindly redo (a dropped stream,
        // a flaky script) and only up to the step's own RetryPolicy count; anything else — and a
        // class whose budget is already spent — falls through to the ordinary terminal FAILED
        // that a human retries manually (T11 acceptance: second stream failure is terminal).
        int maxRetries = autoRetryLimit(def, cmd.reason());
        int spent = ctx.autoRetryCounts.getOrDefault(cmd.stepId(), 0);
        if (maxRetries > spent) {
            ctx.autoRetryCounts.put(cmd.stepId(), spent + 1);
            audit(ctx, cmd.stepId(), cmd.iteration(), "step.retried",
                    AuditPayloads.autoRetriedPayload(spent + 1, maxRetries));
            phaseDispatcher.warnIfPromptDrifted(ctx, def);
            persistAndPublish(ctx);
            dispatch(ctx, def);
        } else {
            sr.markFailed(cmd.reason());
            persistAndPublish(ctx);
        }
        advance(ctx);
    }

    private static int autoRetryLimit(StepDefinition def, FailureReason reason) {
        return switch (def) {
            case AgentStep a -> reason == FailureReason.STREAM ? a.retry().stream() : 0;
            case ScriptStep s -> reason == FailureReason.SCRIPT ? s.retry().script() : 0;
            case OutwardStep o -> reason == FailureReason.SCRIPT ? o.retry().script() : 0;
            default -> 0;
        };
    }

    private void handleCancelRun(RunContext ctx) {
        if (ctx.run.status() == RunStatus.RUNNING) {
            ctx.run.cancel();
            audit(ctx, null, 0, "run.cancelled", AuditPayloads.empty());
            persistAndPublish(ctx);
        }
    }

    private void handleRetryStep(RunContext ctx, EngineCommand.RetryStep cmd) {
        if (ctx.run.status() != RunStatus.RUNNING || !ctx.run.hasStep(cmd.stepId())) {
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.FAILED) {
            log.warn("retry requested for step {} that is not FAILED (status {})", cmd.stepId(), sr.status());
            return;
        }
        if (sr.failureReason().map(FailureReason::blocksManualRetry).orElse(false)) {
            log.warn("retry refused for step {} pending investigation (reason {})", cmd.stepId(), sr.failureReason());
            return;
        }
        ctx.autoRetryCounts.remove(cmd.stepId());
        ctx.resetQuestionRounds(cmd.stepId());
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        phaseDispatcher.warnIfPromptDrifted(ctx, def);
        sr.transitionTo(StepStatus.READY);
        audit(ctx, cmd.stepId(), sr.iteration(), "step.retried", AuditPayloads.retriedPayload(false));
        persistAndPublish(ctx);
        dispatch(ctx, def);
        advance(ctx);
    }

    // ---- readiness ----------------------------------------------------------------------

    void advance(RunContext ctx) {
        if (ctx.run.status() != RunStatus.RUNNING) {
            return;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StepDefinition def : List.copyOf(ctx.stepDefs.values())) {
                StepRun sr = ctx.run.step(def.id());
                if (sr.status() != StepStatus.PENDING || !depsSatisfied(ctx, def)) {
                    continue;
                }
                sr.transitionTo(StepStatus.READY);
                persistAndPublish(ctx);
                ctx.resetQuestionRounds(def.id());
                dispatch(ctx, def);
                changed = true;
            }
        }
        checkCompletion(ctx);
    }

    private boolean depsSatisfied(RunContext ctx, StepDefinition def) {
        for (String dep : def.dependsOn()) {
            if (ctx.run.step(dep).status() != StepStatus.PASSED) {
                return false;
            }
        }
        return true;
    }

    private void checkCompletion(RunContext ctx) {
        if (ctx.run.status() != RunStatus.RUNNING) {
            return;
        }
        boolean allDone = ctx.run.steps().stream()
                .allMatch(s -> s.status() == StepStatus.PASSED || s.status() == StepStatus.SKIPPED);
        if (allDone) {
            ctx.run.complete();
            audit(ctx, null, 0, "run.completed", AuditPayloads.empty());
            persistAndPublish(ctx);
        }
    }

    // ---- dispatch -------------------------------------------------------------------------

    void dispatch(RunContext ctx, StepDefinition def) {
        switch (def) {
            case ScriptStep s -> phaseDispatcher.dispatchScript(ctx, s);
            case AgentStep a -> phaseDispatcher.dispatchAgent(ctx, a);
            case JudgeStep j -> judgeCoordinator.dispatchJudge(ctx, j);
            case GateStep g -> gateAndQuestionCoordinator.dispatchGate(ctx, g);
            case BranchStep b -> phaseDispatcher.dispatchBranch(ctx, b);
            case PerTaskLoop l -> phaseDispatcher.dispatchPerTaskLoop(ctx, l);
            case OutwardStep o -> outwardCoordinator.dispatchOutward(ctx, o);
        }
    }

    void haltOnEngineError(RunContext ctx, String stepId, String detail) {
        log.error("run {} halted: {}", ctx.run.id(), detail);
        ctx.run.pause(RunHaltReason.ENGINE_ERROR);
        audit(ctx, stepId, 0, "run.paused", AuditPayloads.haltPayload(RunHaltReason.ENGINE_ERROR.name(), detail));
        audit(ctx, stepId, 0, "incident.raised", AuditPayloads.failedPayload(FailureReason.SCRIPT, detail));
        persistAndPublish(ctx);
        publish(new EngineEvent.IncidentRaised(ctx.run.id(), stepId, FailureReason.SCRIPT, detail));
    }

    // ---- audit (SD §4: "аудит пишет только движок") ----------------------------------------

    /**
     * Appends one hash-chain audit entry (T07's {@link StateStore#appendAudit}) and, for a
     * step-scoped event, records a pointer to it on that step ({@link StepRun#recordEvent}) so
     * it shows up in the published {@link dev.forgeide.core.run.StepSnapshot#events()}.
     *
     * <p>Call this immediately <em>before</em> the transition's own {@link #persistAndPublish}
     * so the resulting {@link AuditRef} is present in the snapshot that gets persisted and
     * published — {@link RunLifecycle#bootstrap} is the one exception (see its call sites).
     */
    void audit(RunContext ctx, String stepId, int iteration, String type, ObjectNode payload) {
        AuditEvent envelope = new AuditEvent(0, Instant.now(), ctx.run.id(), stepId, iteration, type, payload, "", "");
        long seq = stateStore.appendAudit(envelope);
        // The audit *event* is always written regardless; attaching it to a step's own event
        // list is best-effort — the generic actor-exception catch (FR-11.4) can end up here with
        // a stepId that never resolved to a real step in the first place, and that must not
        // itself become a second crash inside the very handler meant to contain the first one.
        if (stepId != null && ctx.run.hasStep(stepId)) {
            ctx.run.step(stepId).recordEvent(new AuditRef(seq, type));
        }
    }

    // ---- persistence + events ---------------------------------------------------------

    void persistAndPublish(RunContext ctx) {
        RunSnapshot snapshot = ctx.run.snapshot();
        stateStore.save(snapshot);
        latestSnapshots.put(ctx.run.id(), snapshot);
        publish(new EngineEvent.RunUpdated(snapshot));
    }

    void publish(EngineEvent event) {
        for (Consumer<EngineEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                log.warn("engine event listener threw", ex);
            }
        }
    }
}
