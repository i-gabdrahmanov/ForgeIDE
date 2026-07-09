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
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.run.AuditRef;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.JudgeVerdict;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.PipelineRun;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StateStore stateStore;
    private final AgentRuntimePort agentRuntime;
    private final ScriptRunnerPort scriptRunner;
    private final ExecutorService workers;

    private final BlockingQueue<Runnable> mailbox = new LinkedBlockingQueue<>();
    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<RunId, RunContext> runs = new LinkedHashMap<>();
    private final Map<RunId, RunSnapshot> latestSnapshots = new ConcurrentHashMap<>();
    private final Thread actorThread;
    private volatile boolean running = true;

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner) {
        this(stateStore, agentRuntime, scriptRunner, Executors.newVirtualThreadPerTaskExecutor());
    }

    public PipelineEngine(StateStore stateStore, AgentRuntimePort agentRuntime, ScriptRunnerPort scriptRunner,
                           ExecutorService workers) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.agentRuntime = Objects.requireNonNull(agentRuntime, "agentRuntime");
        this.scriptRunner = Objects.requireNonNull(scriptRunner, "scriptRunner");
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

            RunContext ctx = new RunContext(run, project, resolver, stepDefs, promptSnapshots);
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
            RunContext failedCtx = new RunContext(run, project, MapVariableResolver.builder().build(),
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

            RunContext ctx = new RunContext(run, project, resolver, stepDefs, promptSnapshots);
            ctx.templateKeyOf.putAll(templateKeyOf);
            replayContext(ctx, stateStore.loadAudit(runId));

            runs.put(runId, ctx);
            audit(ctx, null, 0, "run.resumed", MAPPER.createObjectNode());
            persistAndPublish(ctx);
            advance(ctx);
        } catch (RuntimeException ex) {
            log.error("failed to resume run {}", runId, ex);
            run.pause(RunHaltReason.ENGINE_ERROR);
            RunContext failedCtx = new RunContext(run, project, MapVariableResolver.builder().build(), Map.of(), Map.of());
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
                case "questions.answered" -> {
                    JsonNode answers = event.payload().get("answers");
                    if (stepId != null && answers != null && answers.isObject()) {
                        Map<String, String> answerMap = new LinkedHashMap<>();
                        answers.fields().forEachRemaining(e -> answerMap.put(e.getKey(), e.getValue().asText()));
                        ctx.lastAnswers.put(stepId, answerMap);
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
            sr.awaitInput(cmd.pendingQuestions());
            audit(ctx, cmd.stepId(), cmd.iteration(), "questions.asked", questionsAskedPayload(cmd.pendingQuestions()));
            persistAndPublish(ctx);
            publish(new EngineEvent.QuestionsPending(ctx.run.id(), cmd.stepId(), cmd.pendingQuestions()));
        } else {
            sr.transitionTo(StepStatus.PASSED);
            ctx.autoRetryCounts.remove(cmd.stepId());
            audit(ctx, cmd.stepId(), cmd.iteration(), "step.completed", MAPPER.createObjectNode());
            persistAndPublish(ctx);
        }
        advance(ctx);
    }

    private static ObjectNode questionsAskedPayload(List<PendingQuestion> questions) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("questions");
        for (PendingQuestion q : questions) {
            ObjectNode qNode = array.addObject();
            qNode.put("id", q.id());
            qNode.put("text", q.text());
            qNode.put("type", q.type().name());
        }
        return payload;
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
            dispatch(ctx, ctx.stepDefs.get(targetId));
            judgeRun.transitionTo(StepStatus.PENDING);
            persistAndPublish(ctx);
        } else {
            judgeRun.transitionTo(StepStatus.WAITING_GATE);
            String question = "Judge exhausted " + judge.failPolicy().maxIterations() + " iteration(s) for step '"
                    + targetId + "'. Retry gives it one more attempt; cancel fails the judge.";
            audit(ctx, judge.id(), judgeRun.iteration(), "gate.requested",
                    gateRequestedPayload(question, List.of("retry", "cancel"), targetId));
            persistAndPublish(ctx);
            publish(new EngineEvent.GateRequest(ctx.run.id(), judge.id(), question,
                    List.of("retry", "cancel"), List.of()));
        }
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
            ctx.gateAnswers.put(cmd.stepId(), cmd.answer());
            sr.transitionTo(StepStatus.PASSED);
            audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
            persistAndPublish(ctx);
        } else if (def instanceof JudgeStep judge) {
            switch (cmd.answer()) {
                case "retry" -> {
                    audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                    StepRun targetRun = ctx.run.step(judge.targetStepId());
                    targetRun.transitionTo(StepStatus.READY);
                    persistAndPublish(ctx);
                    dispatch(ctx, ctx.stepDefs.get(judge.targetStepId()));
                    sr.transitionTo(StepStatus.PENDING);
                    persistAndPublish(ctx);
                }
                case "cancel" -> {
                    audit(ctx, cmd.stepId(), sr.iteration(), "gate.answered", gateAnsweredPayload(cmd));
                    sr.markFailed(FailureReason.JUDGE);
                    persistAndPublish(ctx);
                }
                default -> log.warn("unknown escalation answer '{}' for judge {}", cmd.answer(), cmd.stepId());
            }
        } else {
            log.warn("gate answered for a step that is neither a gate nor an escalated judge: {}", cmd.stepId());
            return;
        }
        advance(ctx);
    }

    private static ObjectNode gateAnsweredPayload(EngineCommand.GateAnswered cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("answer", cmd.answer());
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
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
        ctx.lastAnswers.put(cmd.stepId(), cmd.answers());
        sr.transitionTo(StepStatus.READY);
        audit(ctx, cmd.stepId(), sr.iteration(), "questions.answered", questionsAnsweredPayload(cmd));
        persistAndPublish(ctx);
        dispatch(ctx, ctx.stepDefs.get(cmd.stepId()));
        advance(ctx);
    }

    private static ObjectNode questionsAnsweredPayload(EngineCommand.QuestionsAnswered cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode answers = payload.putObject("answers");
        cmd.answers().forEach(answers::put);
        return payload;
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
        StepDefinition def = ctx.stepDefs.get(cmd.stepId());
        warnIfPromptDrifted(ctx, def);
        sr.transitionTo(StepStatus.READY);
        audit(ctx, cmd.stepId(), sr.iteration(), "step.retried", retriedPayload(false));
        persistAndPublish(ctx);
        dispatch(ctx, def);
        advance(ctx);
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
        workers.execute(() -> {
            try {
                String templateKey = ctx.templateKeyOf.getOrDefault(step.id(), step.id());
                String raw = ctx.promptSnapshots.get(templateKey);
                if (raw == null) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.ARTIFACTS,
                            "no prompt snapshot for " + templateKey));
                    return;
                }
                String prompt = appendContextBlocks(ctx, step.id(), ctx.resolver.render(raw));
                Path logDir = logDir(ctx, step.id(), iteration);
                AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, prompt,
                        step.budget().wallClock(), step.budget().tokens(),
                        step.budget().outputMb() * 1024L * 1024L, logDir, binding.get(), Map.of());
                AgentResult result = agentRuntime.execute(invocation, event -> { });
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
                    List<String> command = check.command().stream().map(ctx.resolver::render).toList();
                    ScriptResult result = scriptRunner.run(
                            new ScriptInvocation(ctx.projectRoot, command, check.timeout(), Map.of()));
                    deterministicPassed = result.exitCode() == 0;
                    detail.append("check exit ").append(result.exitCode());
                }
                if (judge.llmJudge().isPresent()) {
                    AgentStep llm = judge.llmJudge().get();
                    String templateKey = ctx.templateKeyOf.getOrDefault(judge.id(), judge.id()) + ".llm";
                    String raw = ctx.promptSnapshots.getOrDefault(templateKey, "");
                    Path logDir = logDir(ctx, judge.id(), iteration).resolve("llm");
                    AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, ctx.resolver.render(raw),
                            llm.budget().wallClock(), llm.budget().tokens(),
                            llm.budget().outputMb() * 1024L * 1024L, logDir, finalLlmBinding, Map.of());
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
        publish(new EngineEvent.GateRequest(ctx.run.id(), gate.id(), gate.question(), gate.options(), artifacts));
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

    private void dispatchOutward(RunContext ctx, OutwardStep outward) {
        StepRun sr = ctx.run.step(outward.id());
        sr.transitionTo(StepStatus.RUNNING);
        sr.startIteration();
        persistAndPublish(ctx);
        // Real git/PR/Jira execution is T17; the engine only threads the step through to
        // PASSED so the rest of the graph can progress end-to-end ahead of that adapter.
        sr.transitionTo(StepStatus.PASSED);
        persistAndPublish(ctx);
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
