package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.RiskLevel;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.AuditRef;
import dev.forgeide.core.run.EscalationAction;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.JudgeVerdict;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.run.QuestionEscalationAction;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunLogLayout;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepRun;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.core.vars.MapVariableResolver;
import dev.forgeide.core.vars.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** FR-10.5: at most this many {@code pending_questions} rounds per phase attempt before the
     * round-limit escalation dialog takes over (Т-15: "изматывание человека вопросами"). */
    private static final int QUESTION_ROUND_LIMIT = 2;

    private final StateStore stateStore;
    private final AgentRuntimePort agentRuntime;
    private final ScriptRunnerPort scriptRunner;
    private final ManifestProjectorPort manifestProjector;
    private final ScopeDiffPort scopeDiff;
    private final SecretStorePort secretStore;
    private final OutwardActionsPort outwardActions;
    private final ExecutorService workers;

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
                Executors.newVirtualThreadPerTaskExecutor());
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ManifestProjectorPort manifestProjector, ScopeDiffPort scopeDiff, SecretStorePort secretStore,
                           OutwardActionsPort outwardActions, ExecutorService workers) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.agentRuntime = Objects.requireNonNull(agentRuntime, "agentRuntime");
        this.scriptRunner = Objects.requireNonNull(scriptRunner, "scriptRunner");
        this.manifestProjector = Objects.requireNonNull(manifestProjector, "manifestProjector");
        this.scopeDiff = Objects.requireNonNull(scopeDiff, "scopeDiff");
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.outwardActions = Objects.requireNonNull(outwardActions, "outwardActions");
        this.workers = Objects.requireNonNull(workers, "workers");
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
        enqueue(() -> bootstrap(runId, project, definition, featureSlug));
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
        enqueue(() -> rehydrate(project, definition, runId));
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

    // ---- bootstrap ------------------------------------------------------------------------

    private void bootstrap(RunId runId, ProjectDefinition project, PipelineDefinition definition, String featureSlug) {
        List<String> topLevelIds = definition.steps().stream().map(StepDefinition::id).toList();
        PipelineRun run = new PipelineRun(runId, featureSlug, topLevelIds);

        Map<String, StepDefinition> stepDefs = new LinkedHashMap<>();
        definition.steps().forEach(s -> stepDefs.put(s.id(), s));

        try {
            Map<String, Path> promptPaths = new LinkedHashMap<>();
            TemplateExpansion.collectPromptPaths(definition.steps(), "", promptPaths);
            Map<String, String> promptSnapshots = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : promptPaths.entrySet()) {
                promptSnapshots.put(entry.getKey(), readPromptFile(project.repositoryPath(), entry.getValue()));
            }

            MapVariableResolver.Builder resolverBuilder = MapVariableResolver.builder()
                    .project("name", project.name())
                    .project("repository", project.repositoryPath().toString())
                    .feature("slug", featureSlug);
            project.paramValues().forEach(resolverBuilder::param);
            VariableResolver resolver = resolverBuilder.build();

            RunContext ctx = new RunContext(run, project, definition.id(), resolver, stepDefs, promptSnapshots);
            runs.put(runId, ctx);
            persistAndPublish(ctx);
            // appendAudit needs the run directory that the persistAndPublish above just created
            // (FileStateStore.save creates it) — this is the one call site where audit() runs
            // after, not before, its persistAndPublish.
            audit(ctx, null, 0, "run.started", runStartedPayload(featureSlug, definition, topLevelIds));
            advance(ctx);
        } catch (RuntimeException ex) {
            log.error("failed to start run {} for feature {}", runId, featureSlug, ex);
            run.pause(RunHaltReason.ENGINE_ERROR);
            RunContext failedCtx = new RunContext(run, project, definition.id(), MapVariableResolver.builder().build(),
                    stepDefs, Map.of());
            runs.put(runId, failedCtx);
            persistAndPublish(failedCtx);
            audit(failedCtx, null, 0, "run.paused",
                    haltPayload(RunHaltReason.ENGINE_ERROR.name(), String.valueOf(ex.getMessage())));
        }
    }

    // ---- resume (FR-3.4) --------------------------------------------------------------------

    private void rehydrate(ProjectDefinition project, PipelineDefinition definition, RunId runId) {
        if (runs.containsKey(runId)) {
            return;
        }
        Optional<RunSnapshot> loaded = stateStore.load(runId);
        if (loaded.isEmpty()) {
            log.warn("resume requested for unknown run {}", runId);
            return;
        }
        RunSnapshot snapshot = loaded.get();
        PipelineRun run = PipelineRun.restore(snapshot);

        try {
            Map<String, StepDefinition> stepDefs = new LinkedHashMap<>();
            definition.steps().forEach(s -> stepDefs.put(s.id(), s));
            Map<String, String> templateKeyOf = new LinkedHashMap<>();
            for (StepDefinition def : definition.steps()) {
                if (def instanceof PerTaskLoop loop) {
                    reExpandIfAlreadyPassed(project, run, loop, stepDefs, templateKeyOf);
                }
            }

            // FR-3.5's "read once at run start" boundary is this resume, not the original (now
            // gone) process's bootstrap — there is nowhere the literal prompt text of that process
            // could have survived a kill -9, so the current file is what this run's remainder
            // executes with, going forward, from here.
            Map<String, Path> promptPaths = new LinkedHashMap<>();
            TemplateExpansion.collectPromptPaths(definition.steps(), "", promptPaths);
            Map<String, String> promptSnapshots = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : promptPaths.entrySet()) {
                promptSnapshots.put(entry.getKey(), readPromptFile(project.repositoryPath(), entry.getValue()));
            }

            MapVariableResolver.Builder resolverBuilder = MapVariableResolver.builder()
                    .project("name", project.name())
                    .project("repository", project.repositoryPath().toString())
                    .feature("slug", snapshot.featureSlug());
            project.paramValues().forEach(resolverBuilder::param);
            VariableResolver resolver = resolverBuilder.build();

            RunContext ctx = new RunContext(run, project, definition.id(), resolver, stepDefs, promptSnapshots);
            ctx.templateKeyOf.putAll(templateKeyOf);
            replayContext(ctx, stateStore.loadAudit(runId));

            runs.put(runId, ctx);
            audit(ctx, null, 0, "run.resumed", MAPPER.createObjectNode());
            persistAndPublish(ctx);
            advance(ctx);
        } catch (RuntimeException ex) {
            log.error("failed to resume run {}", runId, ex);
            run.pause(RunHaltReason.ENGINE_ERROR);
            RunContext failedCtx = new RunContext(run, project, definition.id(), MapVariableResolver.builder().build(), Map.of(), Map.of());
            runs.put(runId, failedCtx);
            persistAndPublish(failedCtx);
            audit(failedCtx, null, 0, "run.paused",
                    haltPayload(RunHaltReason.ENGINE_ERROR.name(), String.valueOf(ex.getMessage())));
        }
    }

    /** Re-derives the runtime step instances a {@code per_task_loop} already unrolled and passed
     * before the process died — the static {@link PipelineDefinition} alone has no record of
     * them, but re-reading its own {@code task-plan.json} is deterministic (same file the run
     * expanded from originally, modulo FR-3.5 drift, which retry/resume already warns about). */
    private void reExpandIfAlreadyPassed(ProjectDefinition project, PipelineRun run, PerTaskLoop loop,
                                          Map<String, StepDefinition> stepDefs, Map<String, String> templateKeyOf) {
        if (!run.hasStep(loop.id()) || run.step(loop.id()).status() != StepStatus.PASSED) {
            return;
        }
        List<String> taskIds;
        try {
            taskIds = readTaskIds(project.repositoryPath().resolve(loop.taskPlanJson()));
        } catch (IOException ex) {
            throw new UncheckedIOException("resume: failed to re-expand per_task_loop " + loop.id(), ex);
        }
        for (String taskId : taskIds) {
            List<StepDefinition> expanded = TemplateExpansion.expandForTask(loop, taskId);
            for (int i = 0; i < expanded.size(); i++) {
                StepDefinition instance = expanded.get(i);
                stepDefs.put(instance.id(), instance);
                templateKeyOf.put(instance.id(), loop.id() + "/" + loop.template().get(i).id());
            }
        }
    }

    /** Reconstructs the in-memory-only bookkeeping {@link RunContext} normally accumulates live
     * (gate answers, question answers, judge-accumulated errors) by replaying the persisted audit
     * hash-chain — the one part of a run's history {@link RunSnapshot} itself does not carry. */
    private void replayContext(RunContext ctx, List<AuditEvent> auditEvents) {
        for (AuditEvent event : auditEvents) {
            String stepId = event.stepId();
            switch (event.type()) {
                case "gate.answered" -> {
                    JsonNode answer = event.payload().get("answer");
                    if (stepId != null && answer != null && answer.isTextual()) {
                        ctx.gateAnswers.put(stepId, answer.asText());
                    }
                }
                case "question.answered" -> {
                    JsonNode answers = event.payload().get("answers");
                    if (stepId != null && answers != null && answers.isObject()) {
                        Map<String, String> answerMap = new LinkedHashMap<>();
                        answers.fields().forEachRemaining(e -> answerMap.put(e.getKey(), e.getValue().asText()));
                        ctx.lastAnswers.put(stepId, answerMap);
                    }
                }
                case "outward.result" -> {
                    JsonNode branch = event.payload().get("branch");
                    if (stepId != null && branch != null && branch.isTextual()) {
                        ctx.outwardBranches.put(stepId, branch.asText());
                    }
                }
                case "judge.verdict" -> {
                    JsonNode passed = event.payload().get("passed");
                    JsonNode target = event.payload().get("targetStepId");
                    if (target == null) {
                        continue;
                    }
                    if (passed != null && passed.asBoolean(false)) {
                        ctx.accumulatedErrors.remove(target.asText());
                    } else {
                        JsonNode detail = event.payload().get("detail");
                        ctx.accumulatedErrors.computeIfAbsent(target.asText(), k -> new ArrayList<>())
                                .add(detail == null ? "" : detail.asText());
                    }
                }
                default -> { }
            }
        }
    }

    private static ObjectNode runStartedPayload(String featureSlug, PipelineDefinition definition, List<String> stepIds) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("featureSlug", featureSlug);
        payload.put("pipelineId", definition.id());
        ArrayNode ids = payload.putArray("stepIds");
        stepIds.forEach(ids::add);
        return payload;
    }

    private static ObjectNode haltPayload(String reason, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reason", reason);
        payload.put("detail", detail);
        return payload;
    }

    private static String readPromptFile(Path root, Path relative) {
        try {
            return Files.readString(root.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt template: " + relative, e);
        }
    }

    // ---- command handling -------------------------------------------------------------

    private void handle(EngineCommand command) {
        RunContext ctx = runs.get(command.runId());
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
                case EngineCommand.GateAnswered c -> handleGateAnswered(ctx, c);
                case EngineCommand.QuestionsAnswered c -> handleQuestionsAnswered(ctx, c);
                case EngineCommand.EvidenceObserved c -> handleEvidenceObserved(ctx, c);
                case EngineCommand.OutwardCompleted c -> handleOutwardCompleted(ctx, c);
                case EngineCommand.CancelRun c -> handleCancelRun(ctx);
                case EngineCommand.RetryStep c -> handleRetryStep(ctx, c);
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
            case EngineCommand.OutwardCompleted c -> c.stepId();
            case EngineCommand.RetryStep c -> c.stepId();
            case EngineCommand.CancelRun c -> null;
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
            handleJudgeOutcome(ctx, judge, sr, true, "");
        } else if (!cmd.pendingQuestions().isEmpty()) {
            int round = ctx.questionRounds.merge(cmd.stepId(), 1, Integer::sum);
            if (round > QUESTION_ROUND_LIMIT) {
                escalateQuestionRounds(ctx, cmd.stepId(), sr, cmd.pendingQuestions());
            } else {
                sr.awaitInput(cmd.pendingQuestions());
                audit(ctx, cmd.stepId(), cmd.iteration(), "question.asked", questionAskedPayload(cmd.pendingQuestions()));
                persistAndPublish(ctx);
                publish(new EngineEvent.QuestionsPending(ctx.run.id(), cmd.stepId(), cmd.pendingQuestions()));
            }
        } else {
            sr.transitionTo(StepStatus.PASSED);
            ctx.autoRetryCounts.remove(cmd.stepId());
            audit(ctx, cmd.stepId(), cmd.iteration(), "step.completed", MAPPER.createObjectNode());
            persistAndPublish(ctx);
        }
        advance(ctx);
    }

    private static ObjectNode questionAskedPayload(List<PendingQuestion> questions) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("questions");
        for (PendingQuestion q : questions) {
            ObjectNode qNode = array.addObject();
            qNode.put("id", q.id());
            qNode.put("text", q.text());
            qNode.put("type", q.type().name());
            if (!q.options().isEmpty()) {
                ArrayNode options = qNode.putArray("options");
                q.options().forEach(options::add);
            }
            q.context().ifPresent(c -> qNode.put("context", c));
        }
        return payload;
    }

    /** FR-10.5 / Т-15: a 3rd round in the same phase attempt does not ask again — it hands the
     * step to the shared T12 escalation dialog instead, exactly like a judge's exhausted
     * {@code fail_policy} (FR-11.3's "shared infrastructure"), so a model cannot loop a human
     * into exhaustion by endlessly asking one more question. */
    private void escalateQuestionRounds(RunContext ctx, String stepId, StepRun sr, List<PendingQuestion> questions) {
        ctx.questionEscalations.add(stepId);
        sr.transitionTo(StepStatus.WAITING_GATE);
        String question = "Step '" + stepId + "' asked a " + (QUESTION_ROUND_LIMIT + 1)
                + "rd round of questions — the limit is " + QUESTION_ROUND_LIMIT
                + " per attempt (FR-10.5). Choose how to proceed.";
        List<String> options = Arrays.stream(QuestionEscalationAction.values())
                .map(QuestionEscalationAction::token).toList();
        ObjectNode payload = questionAskedPayload(questions);
        ArrayNode optionsNode = payload.putArray("options");
        options.forEach(optionsNode::add);
        audit(ctx, stepId, sr.iteration(), "question.escalated", payload);
        persistAndPublish(ctx);
        List<String> history = List.copyOf(ctx.questionRoundHistory.getOrDefault(stepId, List.of()));
        publish(new EngineEvent.GateRequest(ctx.run.id(), stepId, question, options, List.of(), RiskLevel.R1, history));
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
            handleJudgeOutcome(ctx, judge, sr, false, cmd.detail());
            advance(ctx);
            return;
        }
        audit(ctx, cmd.stepId(), cmd.iteration(), "step.failed", failedPayload(cmd.reason(), cmd.detail()));
        if (cmd.reason() == FailureReason.TAMPERED) {
            // SR-2/Т-1: a manifest-projection hash mismatch is a security incident, not just an
            // ordinary step failure — the canonical incident.tamper event (SDD §5.3) carries the
            // same reason/diff as step.failed above, just filed under its own auditable type so
            // it is easy to find without scanning every step.failed for reason=TAMPERED.
            audit(ctx, cmd.stepId(), cmd.iteration(), "incident.tamper", failedPayload(cmd.reason(), cmd.detail()));
        } else if (cmd.reason() == FailureReason.SCOPE) {
            // SR-6/Т-13: same reasoning as incident.tamper above — a write outside allowed_write
            // (or a HEAD that moved) is a security incident, filed under its own auditable type
            // with the violating paths in detail, ready for the incident dialog's "roll back
            // excess" action.
            audit(ctx, cmd.stepId(), cmd.iteration(), "incident.scope", failedPayload(cmd.reason(), cmd.detail()));
        }

        // FR-11.2: auto-retry only the classes that are safe to blindly redo (a dropped stream,
        // a flaky script) and only up to the step's own RetryPolicy count; anything else — and a
        // class whose budget is already spent — falls through to the ordinary terminal FAILED
        // that a human retries manually (T11 acceptance: second stream failure is terminal).
        int maxRetries = autoRetryLimit(def, cmd.reason());
        int spent = ctx.autoRetryCounts.getOrDefault(cmd.stepId(), 0);
        if (maxRetries > spent) {
            ctx.autoRetryCounts.put(cmd.stepId(), spent + 1);
            audit(ctx, cmd.stepId(), cmd.iteration(), "step.retried", autoRetriedPayload(spent + 1, maxRetries));
            warnIfPromptDrifted(ctx, def);
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

    private static ObjectNode failedPayload(FailureReason reason, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reason", reason.name());
        payload.put("detail", detail);
        return payload;
    }

    private static ObjectNode retriedPayload(boolean auto) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("auto", auto);
        return payload;
    }

    private static ObjectNode autoRetriedPayload(int attempt, int max) {
        ObjectNode payload = retriedPayload(true);
        payload.put("attempt", attempt);
        payload.put("max", max);
        return payload;
    }

    private void handleJudgeOutcome(RunContext ctx, JudgeStep judge, StepRun judgeRun, boolean passed, String detail) {
        judgeRun.recordVerdict(new JudgeVerdict(judgeRun.iteration(), Optional.empty(), passed, detail));
        String targetId = judge.targetStepId();
        StepRun targetRun = ctx.run.step(targetId);
        audit(ctx, judge.id(), judgeRun.iteration(), "judge.verdict", verdictPayload(targetId, passed, detail));

        if (passed) {
            ctx.accumulatedErrors.remove(targetId);
            judgeRun.transitionTo(StepStatus.PASSED);
            persistAndPublish(ctx);
            return;
        }

        ctx.accumulatedErrors.computeIfAbsent(targetId, k -> new ArrayList<>()).add(detail);
        if (judgeRun.iteration() < judge.failPolicy().maxIterations()) {
            targetRun.transitionTo(StepStatus.READY);
            persistAndPublish(ctx);
            resetQuestionRounds(ctx, targetId);
            dispatch(ctx, ctx.stepDefs.get(targetId));
            judgeRun.transitionTo(StepStatus.PENDING);
            persistAndPublish(ctx);
        } else {
            judgeRun.transitionTo(StepStatus.WAITING_GATE);
            String question = "Judge exhausted " + judge.failPolicy().maxIterations() + " iteration(s) for step '"
                    + targetId + "'. Choose how to proceed.";
            List<String> options = Arrays.stream(EscalationAction.values()).map(EscalationAction::token).toList();
            audit(ctx, judge.id(), judgeRun.iteration(), "gate.requested",
                    gateRequestedPayload(question, options, targetId));
            persistAndPublish(ctx);
            List<String> errorsHistory = List.copyOf(ctx.accumulatedErrors.getOrDefault(targetId, List.of()));
            publish(new EngineEvent.GateRequest(ctx.run.id(), judge.id(), question, options,
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

    private static ObjectNode verdictPayload(String targetStepId, boolean passed, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("targetStepId", targetStepId);
        payload.put("passed", passed);
        payload.put("detail", detail);
        return payload;
    }

    private static ObjectNode gateRequestedPayload(String question, List<String> options, String targetStepId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("question", question);
        ArrayNode array = payload.putArray("options");
        options.forEach(array::add);
        payload.put("targetStepId", targetStepId);
        return payload;
    }

    private void handleGateAnswered(RunContext ctx, EngineCommand.GateAnswered cmd) {
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
            audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
            persistAndPublish(ctx);
        } else if (def instanceof JudgeStep judge) {
            if (!handleEscalationAnswer(ctx, judge, sr, cmd)) {
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
        advance(ctx);
    }

    /** FR-10.5 round-limit escalation resolution. {@code split_step}/{@code cancel} both end the
     * phase attempt as {@code FAILED(questions)} (Т-15: "эскалация как FAIL") — a human can still
     * manually retry, which resets the round budget; {@code open_prompt} never legitimately
     * reaches here (see {@link QuestionEscalationAction}'s javadoc). */
    private boolean handleQuestionEscalationAnswer(RunContext ctx, StepRun sr, EngineCommand.GateAnswered cmd) {
        Optional<QuestionEscalationAction> action = QuestionEscalationAction.fromToken(cmd.answer());
        if (action.isEmpty()) {
            log.warn("unknown question-escalation answer '{}' for step {}", cmd.answer(), cmd.stepId());
            return false;
        }
        if (action.get() == QuestionEscalationAction.OPEN_PROMPT) {
            log.warn("open_prompt question-escalation answer reached the engine for step {} — "
                    + "T20's prompt editor is out of scope, refusing", cmd.stepId());
            return false;
        }
        audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
        ctx.questionEscalations.remove(cmd.stepId());
        ctx.questionRounds.remove(cmd.stepId());
        ctx.questionRoundHistory.remove(cmd.stepId());
        sr.markFailed(FailureReason.QUESTIONS);
        persistAndPublish(ctx);
        return true;
    }

    /**
     * FR-11.3 escalation resolution — the same {@code GateAnswered} command a real gate answers
     * with, dispatched over {@link EscalationAction} instead of the gate's own free-form options.
     * Returns {@code false} for an answer the engine refuses (unknown token, or a mandatory
     * {@code detail} missing/blank) so the caller can skip the generic {@link #advance} — the
     * step is left exactly as it was, still {@code WAITING_GATE}.
     */
    private boolean handleEscalationAnswer(RunContext ctx, JudgeStep judge, StepRun sr, EngineCommand.GateAnswered cmd) {
        Optional<EscalationAction> action = EscalationAction.fromToken(cmd.answer());
        if (action.isEmpty()) {
            log.warn("unknown escalation answer '{}' for judge {}", cmd.answer(), cmd.stepId());
            return false;
        }
        String targetId = judge.targetStepId();
        switch (action.get()) {
            case RETRY -> {
                audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                retryEscalationTarget(ctx, judge, sr);
            }
            case EDIT_PROMPT -> {
                String edited = cmd.detail().orElse("");
                if (edited.isBlank()) {
                    log.warn("edit_prompt escalation for {} rejected: replacement prompt is blank", cmd.stepId());
                    return false;
                }
                ctx.promptOverrides.put(targetId, edited);
                audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                retryEscalationTarget(ctx, judge, sr);
            }
            case RESET_CHAIN -> {
                ctx.accumulatedErrors.remove(targetId);
                sr.resetIteration();
                audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                retryEscalationTarget(ctx, judge, sr);
            }
            case CANCEL -> {
                audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                sr.markFailed(FailureReason.JUDGE);
                persistAndPublish(ctx);
            }
            case OVERRIDE -> {
                String reason = cmd.detail().orElse("");
                if (reason.isBlank()) {
                    log.warn("override for {} rejected: reason is mandatory (FR-11.3/Т-17)", cmd.stepId());
                    return false;
                }
                audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                audit(ctx, targetId, ctx.run.step(targetId).iteration(), "judge.overridden",
                        overridePayload(judge.id(), reason));
                ctx.accumulatedErrors.remove(targetId);
                ctx.run.step(targetId).transitionTo(StepStatus.PASSED);
                sr.transitionTo(StepStatus.PASSED);
                persistAndPublish(ctx);
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
        persistAndPublish(ctx);
        resetQuestionRounds(ctx, judge.targetStepId());
        dispatch(ctx, ctx.stepDefs.get(judge.targetStepId()));
        escalationRun.transitionTo(StepStatus.PENDING);
        persistAndPublish(ctx);
    }

    private static ObjectNode gateAnsweredPayload(EngineCommand.GateAnswered cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("answer", cmd.answer());
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        cmd.detail().ifPresent(d -> payload.put("detail", d));
        payload.put("diffAcked", cmd.diffAcked());
        return payload;
    }

    private static ObjectNode overridePayload(String judgeStepId, String reason) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("judgeStepId", judgeStepId);
        payload.put("reason", reason);
        return payload;
    }

    private void handleQuestionsAnswered(RunContext ctx, EngineCommand.QuestionsAnswered cmd) {
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
        audit(ctx, cmd.stepId(), sr.iteration(), "question.answered", questionAnsweredPayload(cmd));
        persistAndPublish(ctx);
        dispatch(ctx, ctx.stepDefs.get(cmd.stepId()));
        advance(ctx);
    }

    private static ObjectNode questionAnsweredPayload(EngineCommand.QuestionsAnswered cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        ObjectNode answers = payload.putObject("answers");
        cmd.answers().forEach(answers::put);
        return payload;
    }

    /** T15 scope: records a {@code _origins/<stepId>.json} evidence sighting without touching
     * the step's status — the audit trail gets a pointer to it, but the PASSED/FAILED decision
     * stays exactly what the ordinary dispatch flow already computed ("движок ... переходы
     * делает сам"). Silently ignored for a stale/unknown step, same spirit as a stale {@code
     * StepCompleted}/{@code StepFailed} (not worth halting the run over a race with a slow hook). */
    private void handleEvidenceObserved(RunContext ctx, EngineCommand.EvidenceObserved cmd) {
        if (!ctx.run.hasStep(cmd.stepId())) {
            return;
        }
        audit(ctx, cmd.stepId(), cmd.iteration(), "evidence.origin", cmd.payload());
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

    private void handleCancelRun(RunContext ctx) {
        if (ctx.run.status() == RunStatus.RUNNING) {
            ctx.run.cancel();
            audit(ctx, null, 0, "run.cancelled", MAPPER.createObjectNode());
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
        resetQuestionRounds(ctx, cmd.stepId());
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        warnIfPromptDrifted(ctx, def);
        sr.transitionTo(StepStatus.READY);
        audit(ctx, cmd.stepId(), sr.iteration(), "step.retried", retriedPayload(false));
        persistAndPublish(ctx);
        dispatch(ctx, def);
        advance(ctx);
    }

    /** FR-10.5: a fresh attempt (initial dispatch, judge retry, manual retry) gets a fresh
     * question-round budget — only a question-answer redispatch (which calls {@link #dispatch}
     * directly, bypassing this) continues accumulating rounds within the same attempt. */
    private static void resetQuestionRounds(RunContext ctx, String stepId) {
        ctx.questionRounds.remove(stepId);
        ctx.questionRoundHistory.remove(stepId);
    }

    /**
     * FR-3.5 traceability row T-12: a retry (auto or manual) still executes with the prompt text
     * snapshotted at run start — determinism within a run is non-negotiable — but if the file on
     * disk has since changed, that is worth a loud warning in the timeline rather than a silent
     * "why didn't my edit take effect".
     */
    private void warnIfPromptDrifted(RunContext ctx, StepDefinition def) {
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
            current = readPromptFile(ctx.projectRoot, step.promptTemplate());
        } catch (RuntimeException ex) {
            return;
        }
        if (!snapshot.equals(current)) {
            audit(ctx, step.id(), 0, "prompt.drift",
                    promptDriftPayload(step.promptTemplate().toString(), snapshot, current));
        }
    }

    private static ObjectNode promptDriftPayload(String path, String snapshot, String current) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("promptPath", path);
        payload.put("diff", PromptDiff.summarize(snapshot, current));
        return payload;
    }

    // ---- readiness ----------------------------------------------------------------------

    private void advance(RunContext ctx) {
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
                resetQuestionRounds(ctx, def.id());
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
            audit(ctx, null, 0, "run.completed", MAPPER.createObjectNode());
            persistAndPublish(ctx);
        }
    }

    // ---- dispatch -------------------------------------------------------------------------

    private void dispatch(RunContext ctx, StepDefinition def) {
        switch (def) {
            case ScriptStep s -> dispatchScript(ctx, s);
            case AgentStep a -> dispatchAgent(ctx, a);
            case JudgeStep j -> dispatchJudge(ctx, j);
            case GateStep g -> dispatchGate(ctx, g);
            case BranchStep b -> dispatchBranch(ctx, b);
            case PerTaskLoop l -> dispatchPerTaskLoop(ctx, l);
            case OutwardStep o -> dispatchOutward(ctx, o);
        }
    }

    private int markRunning(RunContext ctx, String stepId) {
        StepRun sr = ctx.run.step(stepId);
        sr.transitionTo(StepStatus.RUNNING);
        sr.startIteration();
        audit(ctx, stepId, sr.iteration(), "step.running", MAPPER.createObjectNode());
        persistAndPublish(ctx);
        return sr.iteration();
    }

    private void dispatchScript(RunContext ctx, ScriptStep step) {
        RunId runId = ctx.run.id();
        int iteration = markRunning(ctx, step.id());
        workers.execute(() -> {
            try {
                List<String> command = step.command().stream().map(ctx.resolver::render).toList();
                ScriptInvocation inv = new ScriptInvocation(ctx.projectRoot, command, step.timeout(), Map.of());
                ScriptResult result = scriptRunner.run(inv);
                if (result.exitCode() == 0) {
                    submit(new EngineCommand.StepCompleted(runId, step.id(), iteration, List.of()));
                } else {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.SCRIPT,
                            "exit " + result.exitCode() + ": " + excerpt(result.stderr(), result.stdout())));
                }
            } catch (ScriptRunnerException | RuntimeException ex) {
                submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.SCRIPT,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    private void dispatchAgent(RunContext ctx, AgentStep step) {
        Optional<RuntimeBinding> binding = ctx.project.runtime(step.runtimeRef());
        if (binding.isEmpty()) {
            haltOnEngineError(ctx, step.id(), "unknown runtime '" + step.runtimeRef() + "'");
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
                // FR-11.3 "edit_prompt" escalation action: a one-shot human replacement for this
                // single attempt, consumed here rather than left to leak into later iterations.
                String raw = ctx.promptOverrides.remove(step.id());
                if (raw == null) {
                    raw = ctx.promptSnapshots.get(templateKey);
                }
                if (raw == null) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.ARTIFACTS,
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

                // SR-6/Т-13: checked right after the phase, ahead of the tamper check — a write
                // outside allowed_write (or a HEAD that moved, e.g. a local commit/reset) is a
                // security incident whether or not the phase's own contract otherwise looks fine.
                List<String> scopeViolations = scopeDiff.violations(ctx.projectRoot, prePhaseScope, step.allowedWrite());
                if (!scopeViolations.isEmpty()) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.SCOPE,
                            "write(s) outside allowed_write: " + String.join(", ", scopeViolations)));
                    return;
                }

                // SR-2/Т-1: tamper-check right after the phase, before any other verdict on the
                // result — a control-plane write outside state-write-guard is a security incident
                // worth catching even when the phase's own artifacts/budget would otherwise pass.
                Optional<String> tamperDiff = manifestProjector.verifyAndRestore(ctx.projectRoot, ctx.pipelineId,
                        ctx.run.featureSlug(), prePhaseSnapshot, expectedManifestHash);
                if (tamperDiff.isPresent()) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.TAMPERED,
                            tamperDiff.get()));
                    return;
                }
                manifestProjector.readOrigin(ctx.projectRoot, ctx.pipelineId, ctx.run.featureSlug(), step.id())
                        .ifPresent(origin -> submit(
                                new EngineCommand.EvidenceObserved(runId, step.id(), iteration, origin)));

                if (result.usage().total() > step.budget().tokens()) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.BUDGET,
                            "token usage " + result.usage().total() + " exceeded budget " + step.budget().tokens()));
                    return;
                }
                if (result.finalJson().isEmpty()) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.STREAM,
                            "no result event"));
                    return;
                }
                Optional<String> artifactError = ArtifactValidation.validate(ctx.projectRoot, step.expectedArtifacts());
                if (artifactError.isPresent()) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.ARTIFACTS,
                            artifactError.get()));
                    return;
                }
                List<PendingQuestion> questions = PendingQuestions.parse(result.finalJson().get());
                submit(new EngineCommand.StepCompleted(runId, step.id(), iteration, questions));
            } catch (AgentRuntimeException | RuntimeException ex) {
                submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.STREAM,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    /** {@code ground/ai-logs/<feature>/iter-NN/<step>/} (SD §6.2). */
    private static Path logDir(RunContext ctx, String stepId, int iteration) {
        return RunLogLayout.stepLogDir(ctx.projectRoot, ctx.run.featureSlug(), stepId, iteration);
    }

    private String appendContextBlocks(RunContext ctx, String stepId, String rendered) {
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

    private void dispatchJudge(RunContext ctx, JudgeStep judge) {
        RuntimeBinding llmBinding = null;
        if (judge.llmJudge().isPresent()) {
            String ref = judge.llmJudge().get().runtimeRef();
            Optional<RuntimeBinding> resolved = ctx.project.runtime(ref);
            if (resolved.isEmpty()) {
                haltOnEngineError(ctx, judge.id(), "unknown runtime '" + ref + "'");
                return;
            }
            llmBinding = resolved.get();
        }
        RunId runId = ctx.run.id();
        int iteration = markRunning(ctx, judge.id());
        RuntimeBinding finalLlmBinding = llmBinding;
        workers.execute(() -> {
            try {
                boolean deterministicPassed = true;
                StringBuilder detail = new StringBuilder();
                if (judge.deterministicCheck().isPresent()) {
                    ScriptStep check = judge.deterministicCheck().get();
                    // TODO(T18): SR-7 wants judge/preflight scripts run from the IDE harness cache
                    // (~/.forgeide/harness-cache/<hash>/), not the project working copy, so a
                    // compromised phase can't edit the very check that grades it. Until T18 builds
                    // that cache, this resolves the command against the project root as-is.
                    List<String> command = check.command().stream().map(ctx.resolver::render).toList();
                    ScriptResult result = scriptRunner.run(
                            new ScriptInvocation(ctx.projectRoot, command, check.timeout(), Map.of()));
                    deterministicPassed = result.exitCode() == 0;
                    detail.append("check exit ").append(result.exitCode());
                    // The exit code alone gives the agent nothing to act on for the next
                    // iteration's accumulated_errors block (FR-4.5) — carry the check's own
                    // diagnostic output too, since that is what actually names the problem.
                    String output = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                    if (!output.isBlank()) {
                        detail.append(": ").append(output.strip());
                    }
                }
                if (judge.llmJudge().isPresent()) {
                    AgentStep llm = judge.llmJudge().get();
                    String templateKey = ctx.templateKeyOf.getOrDefault(judge.id(), judge.id()) + ".llm";
                    String raw = ctx.promptSnapshots.getOrDefault(templateKey, "");
                    Path logDir = logDir(ctx, judge.id(), iteration).resolve("llm");
                    AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, ctx.resolver.render(raw),
                            llm.budget().wallClock(), llm.budget().tokens(),
                            llm.budget().outputMb() * 1024L * 1024L, logDir, finalLlmBinding,
                            secretStore.resolve(llm.envScope()));
                    AgentResult result = agentRuntime.execute(invocation, event -> { });
                    boolean llmPassed = result.finalJson().map(PipelineEngine::isLlmPass).orElse(false);
                    if (!detail.isEmpty()) {
                        detail.append("; ");
                    }
                    detail.append("llm=").append(llmPassed);
                    if (judge.deterministicCheck().isEmpty()) {
                        deterministicPassed = llmPassed;
                    }
                }
                if (detail.isEmpty()) {
                    detail.append("no checks configured");
                }
                if (deterministicPassed) {
                    submit(new EngineCommand.StepCompleted(runId, judge.id(), iteration, List.of()));
                } else {
                    submit(new EngineCommand.StepFailed(runId, judge.id(), iteration, FailureReason.JUDGE,
                            detail.toString()));
                }
            } catch (ScriptRunnerException | AgentRuntimeException | RuntimeException ex) {
                submit(new EngineCommand.StepFailed(runId, judge.id(), iteration, FailureReason.JUDGE,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    private static boolean isLlmPass(JsonNode finalJson) {
        JsonNode verdict = finalJson.get("verdict");
        return verdict != null && verdict.isTextual() && "pass".equalsIgnoreCase(verdict.asText());
    }

    private void dispatchGate(RunContext ctx, GateStep gate) {
        StepRun sr = ctx.run.step(gate.id());
        sr.transitionTo(StepStatus.WAITING_GATE);
        audit(ctx, gate.id(), sr.iteration(), "gate.requested", gateRequestedPayload(gate.question(), gate.options(), gate.id()));
        persistAndPublish(ctx);
        List<Path> artifacts = gate.showArtifacts().stream().map(ctx.projectRoot::resolve).toList();
        publish(new EngineEvent.GateRequest(ctx.run.id(), gate.id(), gate.question(), gate.options(), artifacts,
                gate.risk(), List.of()));
    }

    private void dispatchBranch(RunContext ctx, BranchStep branch) {
        StepRun sr = ctx.run.step(branch.id());
        String answer = branch.dependsOn().stream()
                .filter(ctx.gateAnswers::containsKey)
                .map(ctx.gateAnswers::get)
                .findFirst()
                .orElse(null);
        if (answer == null) {
            haltOnEngineError(ctx, branch.id(),
                    "branch has no recorded gate answer among its depends_on");
            return;
        }
        String chosen = branch.routes().get(answer);
        if (chosen == null) {
            haltOnEngineError(ctx, branch.id(), "no route for answer '" + answer + "'");
            return;
        }
        for (String target : new LinkedHashSet<>(branch.routes().values())) {
            if (!target.equals(chosen) && ctx.run.hasStep(target) && ctx.run.step(target).status() == StepStatus.PENDING) {
                ctx.run.step(target).transitionTo(StepStatus.SKIPPED);
                persistAndPublish(ctx);
            }
        }
        // A chosen target that is already terminal (a loop back to an earlier, already-PASSED
        // step) is not re-armed here — see TemplateExpansion/BranchStep docs: T06 only routes
        // forward, it does not reset the whole cycle behind the branch.
        sr.transitionTo(StepStatus.PASSED);
        persistAndPublish(ctx);
    }

    private void haltOnEngineError(RunContext ctx, String stepId, String detail) {
        log.error("run {} halted: {}", ctx.run.id(), detail);
        ctx.run.pause(RunHaltReason.ENGINE_ERROR);
        audit(ctx, stepId, 0, "run.paused", haltPayload(RunHaltReason.ENGINE_ERROR.name(), detail));
        audit(ctx, stepId, 0, "incident.raised", failedPayload(FailureReason.SCRIPT, detail));
        persistAndPublish(ctx);
        publish(new EngineEvent.IncidentRaised(ctx.run.id(), stepId, FailureReason.SCRIPT, detail));
    }

    private void dispatchPerTaskLoop(RunContext ctx, PerTaskLoop loop) {
        StepRun sr = ctx.run.step(loop.id());
        sr.transitionTo(StepStatus.RUNNING);
        sr.startIteration();
        persistAndPublish(ctx);
        try {
            List<String> taskIds = readTaskIds(ctx.projectRoot.resolve(loop.taskPlanJson()));
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
            persistAndPublish(ctx);
        } catch (IOException | RuntimeException ex) {
            log.error("per_task_loop {} failed to expand", loop.id(), ex);
            sr.markFailed(FailureReason.ARTIFACTS);
            persistAndPublish(ctx);
        }
    }

    private static List<String> readTaskIds(Path taskPlanFile) throws IOException {
        JsonNode root = MAPPER.readTree(taskPlanFile.toFile());
        List<String> ids = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode node : root) {
                if (node.isObject() && node.hasNonNull("id")) {
                    ids.add(node.get("id").asText());
                } else if (node.isTextual()) {
                    ids.add(node.asText());
                }
            }
        }
        return ids;
    }

    /**
     * T17/SR-4: {@code outward} actions run here, on the engine's own worker thread — never
     * inside an agent phase's process. Before touching anything external, re-checks (defense in
     * depth, SD §4's "the engine has the final say", not just {@link
     * dev.forgeide.core.pipeline.validation.PipelineValidator}'s load-time graph check) that
     * every judge upstream of this step actually reached {@code PASSED}; a {@code SCOPE}/{@code
     * TAMPERED} failure anywhere upstream can never reach here in the first place (those block
     * manual retry — {@link FailureReason#blocksManualRetry}), so that half of the T17 scope
     * note's precondition ("чистый scope-diff последней агент-фазы") is structural, not something
     * this method can meaningfully re-verify on its own.
     */
    private void dispatchOutward(RunContext ctx, OutwardStep outward) {
        RunId runId = ctx.run.id();
        int iteration = markRunning(ctx, outward.id());

        Optional<String> unpassedJudge = firstUnpassedUpstreamJudge(ctx, outward);
        if (unpassedJudge.isPresent()) {
            submit(new EngineCommand.StepFailed(runId, outward.id(), iteration, FailureReason.JUDGE,
                    "upstream judge not passed: " + unpassedJudge.get()));
            return;
        }

        String branch = outwardBranch(ctx, outward);
        String prBase = stackedPrBase(ctx, outward);
        Map<String, String> env = secretStore.resolve(outward.envScope());
        workers.execute(() -> {
            try {
                Map<String, String> resultRefs = new LinkedHashMap<>();
                for (OutwardAction action : outward.actions()) {
                    switch (action) {
                        case GIT_PUSH -> resultRefs.putAll(runGitPush(ctx, outward, branch, env));
                        case CREATE_PR -> resultRefs.putAll(runCreatePr(ctx, outward, branch, prBase, env));
                        case JIRA_COMMENT -> resultRefs.putAll(runJiraComment(ctx, outward, resultRefs, env));
                        case JIRA_TRANSITION -> resultRefs.putAll(runJiraTransition(ctx, outward, env));
                    }
                }
                submit(new EngineCommand.OutwardCompleted(runId, outward.id(), iteration, branch, resultRefs));
            } catch (OutwardActionException | RuntimeException ex) {
                submit(new EngineCommand.StepFailed(runId, outward.id(), iteration, FailureReason.SCRIPT,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    /** BFS over {@code dependsOn} (same shape as {@code PipelineValidator#upstreamJudge}, just
     * over live {@link StepStatus} instead of the static graph) for the first judge that has not
     * reached {@code PASSED}. Empty in the overwhelming common case — {@link #depsSatisfied}
     * already guarantees every direct dependency is {@code PASSED} before {@link #dispatch} ever
     * runs — this only ever fires if a future change loosens that guarantee. */
    private Optional<String> firstUnpassedUpstreamJudge(RunContext ctx, StepDefinition outward) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(outward.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            StepDefinition step = ctx.stepDefs.get(id);
            if (step == null) {
                continue;
            }
            if (step instanceof JudgeStep && ctx.run.step(id).status() != StepStatus.PASSED) {
                return Optional.of(id);
            }
            queue.addAll(step.dependsOn());
        }
        return Optional.empty();
    }

    /** Deterministic, per-outward-step branch name: stable across a retry (same commit, same
     * branch — the entire idempotency argument for {@code git_push}/{@code create_pr}), unique
     * across sibling outward steps in the same run (a {@code per_task_loop} can expand several). */
    private static String outwardBranch(RunContext ctx, OutwardStep outward) {
        return ctx.run.featureSlug() + "/" + outward.id();
    }

    /** T17 "stacked по depends_on": if an earlier outward step in this run's {@code dependsOn}
     * closure already pushed a branch, {@code create_pr} bases on top of it instead of the
     * project's single configured target branch — the stacked-PR pattern feature-pipeline's own
     * multi-task delivery relies on. Falls back to {@link dev.forgeide.core.project.OutwardConfig#targetBranch()}
     * when no such predecessor exists. */
    private String stackedPrBase(RunContext ctx, OutwardStep outward) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(outward.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            String branch = ctx.outwardBranches.get(id);
            if (branch != null) {
                return branch;
            }
            StepDefinition step = ctx.stepDefs.get(id);
            if (step == null) {
                continue;
            }
            queue.addAll(step.dependsOn());
        }
        return ctx.project.outward().targetBranch();
    }

    private Map<String, String> runGitPush(RunContext ctx, OutwardStep outward, String branch,
                                            Map<String, String> env) throws OutwardActionException {
        String remote = ctx.project.outward().gitRemote();
        String message = "forgeide: " + ctx.run.featureSlug() + " (" + outward.id() + ")";
        var request = new OutwardActionsPort.GitPushRequest(ctx.projectRoot, remote, branch, message, env);
        return outwardActions.gitPush(request).resultRefs();
    }

    private Map<String, String> runCreatePr(RunContext ctx, OutwardStep outward, String branch, String base,
                                             Map<String, String> env) throws OutwardActionException {
        var repo = ctx.project.outward().pr().orElseThrow(() -> new OutwardActionException(
                "outward step '" + outward.id() + "': create_pr requires the project's outward.pr config"));
        String title = "forgeide: " + ctx.run.featureSlug();
        String body = "Automated delivery via ForgeIDE outward step '" + outward.id() + "' (run " + ctx.run.id() + ").";
        var request = new OutwardActionsPort.CreatePrRequest(ctx.projectRoot, repo, branch, base, title, body, env);
        return outwardActions.createPr(request).resultRefs();
    }

    private Map<String, String> runJiraComment(RunContext ctx, OutwardStep outward, Map<String, String> resultRefsSoFar,
                                                Map<String, String> env) throws OutwardActionException {
        var jira = ctx.project.outward().jira().orElseThrow(() -> new OutwardActionException(
                "outward step '" + outward.id() + "': jira_comment requires the project's outward.jira config"));
        String issueKey = ctx.resolver.render("${params.jira_key}");
        String prUrl = resultRefsSoFar.get("pr_url");
        String comment = "ForgeIDE: delivered run " + ctx.run.id() + " (" + ctx.run.featureSlug() + ")."
                + (prUrl != null ? " PR: " + prUrl : "");
        var request = new OutwardActionsPort.JiraCommentRequest(jira, issueKey, comment, env);
        return outwardActions.jiraComment(request).resultRefs();
    }

    private Map<String, String> runJiraTransition(RunContext ctx, OutwardStep outward, Map<String, String> env)
            throws OutwardActionException {
        var jira = ctx.project.outward().jira().orElseThrow(() -> new OutwardActionException(
                "outward step '" + outward.id() + "': jira_transition requires the project's outward.jira config"));
        String issueKey = ctx.resolver.render("${params.jira_key}");
        var request = new OutwardActionsPort.JiraTransitionRequest(jira, issueKey, env);
        return outwardActions.jiraTransition(request).resultRefs();
    }

    private void handleOutwardCompleted(RunContext ctx, EngineCommand.OutwardCompleted cmd) {
        if (ctx.run.status() != RunStatus.RUNNING) {
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.RUNNING || sr.iteration() != cmd.iteration()) {
            log.debug("stale OutwardCompleted for {} iter {} (current {} iter {})",
                    cmd.stepId(), cmd.iteration(), sr.status(), sr.iteration());
            return;
        }
        ctx.outwardBranches.put(cmd.stepId(), cmd.branch());
        sr.transitionTo(StepStatus.PASSED);
        ctx.autoRetryCounts.remove(cmd.stepId());
        audit(ctx, cmd.stepId(), cmd.iteration(), "outward.result", outwardResultPayload(cmd));
        persistAndPublish(ctx);
        advance(ctx);
    }

    private static ObjectNode outwardResultPayload(EngineCommand.OutwardCompleted cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("branch", cmd.branch());
        ObjectNode refs = payload.putObject("resultRefs");
        cmd.resultRefs().forEach(refs::put);
        return payload;
    }

    private static String excerpt(String stderr, String stdout) {
        String text = stderr != null && !stderr.isBlank() ? stderr : stdout;
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    // ---- audit (SD §4: "аудит пишет только движок") ----------------------------------------

    /**
     * Appends one hash-chain audit entry (T07's {@link StateStore#appendAudit}) and, for a
     * step-scoped event, records a pointer to it on that step ({@link StepRun#recordEvent}) so
     * it shows up in the published {@link dev.forgeide.core.run.StepSnapshot#events()}.
     *
     * <p>Call this immediately <em>before</em> the transition's own {@link #persistAndPublish}
     * so the resulting {@link AuditRef} is present in the snapshot that gets persisted and
     * published — {@link #bootstrap} is the one exception (see its call sites).
     */
    private void audit(RunContext ctx, String stepId, int iteration, String type, ObjectNode payload) {
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

    private void persistAndPublish(RunContext ctx) {
        RunSnapshot snapshot = ctx.run.snapshot();
        stateStore.save(snapshot);
        latestSnapshots.put(ctx.run.id(), snapshot);
        publish(new EngineEvent.RunUpdated(snapshot));
    }

    private void publish(EngineEvent event) {
        for (Consumer<EngineEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                log.warn("engine event listener threw", ex);
            }
        }
    }
}
