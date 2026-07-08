package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.JudgeVerdict;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
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

    /** Posts a command from the UI or a step executor into the actor's mailbox. */
    public void submit(EngineCommand command) {
        Objects.requireNonNull(command, "command");
        enqueue(() -> handle(command));
    }

    /** Registers a listener for every published event, across all runs. */
    public void subscribe(Consumer<EngineEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
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

            RunContext ctx = new RunContext(run, project.repositoryPath(), resolver, stepDefs, promptSnapshots);
            runs.put(runId, ctx);
            persistAndPublish(ctx);
            advance(ctx);
        } catch (RuntimeException ex) {
            log.error("failed to start run {} for feature {}", runId, featureSlug, ex);
            run.pause(RunHaltReason.ENGINE_ERROR);
            runs.put(runId, new RunContext(run, project.repositoryPath(), MapVariableResolver.builder().build(),
                    stepDefs, Map.of()));
            persistAndPublish(runs.get(runId));
        }
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
        switch (command) {
            case EngineCommand.StepCompleted c -> handleStepCompleted(ctx, c);
            case EngineCommand.StepFailed c -> handleStepFailed(ctx, c);
            case EngineCommand.GateAnswered c -> handleGateAnswered(ctx, c);
            case EngineCommand.QuestionsAnswered c -> handleQuestionsAnswered(ctx, c);
            case EngineCommand.CancelRun c -> handleCancelRun(ctx);
            case EngineCommand.RetryStep c -> handleRetryStep(ctx, c);
        }
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
            persistAndPublish(ctx);
            publish(new EngineEvent.QuestionsPending(ctx.run.id(), cmd.stepId(), cmd.pendingQuestions()));
        } else {
            sr.transitionTo(StepStatus.PASSED);
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
            handleJudgeOutcome(ctx, judge, sr, false, cmd.detail());
        } else {
            sr.markFailed(cmd.reason());
            persistAndPublish(ctx);
        }
        advance(ctx);
    }

    private void handleJudgeOutcome(RunContext ctx, JudgeStep judge, StepRun judgeRun, boolean passed, String detail) {
        judgeRun.recordVerdict(new JudgeVerdict(judgeRun.iteration(), Optional.empty(), passed, detail));
        String targetId = judge.targetStepId();
        StepRun targetRun = ctx.run.step(targetId);

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
            persistAndPublish(ctx);
            publish(new EngineEvent.GateRequest(ctx.run.id(), judge.id(),
                    "Judge exhausted " + judge.failPolicy().maxIterations() + " iteration(s) for step '"
                            + targetId + "'. Retry gives it one more attempt; cancel fails the judge.",
                    List.of("retry", "cancel"), List.of()));
        }
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
            persistAndPublish(ctx);
        } else if (def instanceof JudgeStep judge) {
            switch (cmd.answer()) {
                case "retry" -> {
                    StepRun targetRun = ctx.run.step(judge.targetStepId());
                    targetRun.transitionTo(StepStatus.READY);
                    persistAndPublish(ctx);
                    dispatch(ctx, ctx.stepDefs.get(judge.targetStepId()));
                    sr.transitionTo(StepStatus.PENDING);
                    persistAndPublish(ctx);
                }
                case "cancel" -> {
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
        persistAndPublish(ctx);
        dispatch(ctx, ctx.stepDefs.get(cmd.stepId()));
        advance(ctx);
    }

    private void handleCancelRun(RunContext ctx) {
        if (ctx.run.status() == RunStatus.RUNNING) {
            ctx.run.cancel();
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
        sr.transitionTo(StepStatus.READY);
        persistAndPublish(ctx);
        dispatch(ctx, ctx.stepDefs.get(cmd.stepId()));
        advance(ctx);
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
                AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, prompt,
                        step.budget().wallClock(), step.budget().tokens(), Map.of());
                AgentResult result = agentRuntime.execute(invocation, event -> { });
                if (result.finalJson().isEmpty()) {
                    submit(new EngineCommand.StepFailed(runId, step.id(), iteration, FailureReason.STREAM,
                            "no result event"));
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
        RunId runId = ctx.run.id();
        int iteration = markRunning(ctx, judge.id());
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
                    AgentInvocation invocation = new AgentInvocation(ctx.projectRoot, ctx.resolver.render(raw),
                            llm.budget().wallClock(), llm.budget().tokens(), Map.of());
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
