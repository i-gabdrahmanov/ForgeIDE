package dev.forgeide.core.engine;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.vars.VariableResolver;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-run bookkeeping the engine actor needs on top of the domain {@link PipelineRun}
 * (SD §2-3, T06). Touched only from the actor thread — same invariant as {@code PipelineRun}
 * itself. Not part of the public API: everything outside {@code engine} only ever sees a
 * {@link dev.forgeide.core.run.RunSnapshot}.
 */
final class RunContext {

    final PipelineRun run;
    /** Kept (not just {@code repositoryPath}) so dispatch can resolve a step's {@code
     * runtime:} ref to the project's configured {@link dev.forgeide.core.project.RuntimeBinding} (T09). */
    final ProjectDefinition project;
    final Path projectRoot;
    final VariableResolver resolver;
    /** {@link dev.forgeide.core.pipeline.PipelineDefinition#id()} — the {@code <pipeline>}
     * path segment for the manifest projection (SD §4, T15). */
    final String pipelineId;

    /** Grows as {@link dev.forgeide.core.pipeline.PerTaskLoop} steps unroll (FR-3.5 covers only the static definition). */
    final Map<String, StepDefinition> stepDefs;

    /** {@code templateKey -> raw prompt text}, read once at start (FR-3.5); rendered per dispatch. */
    final Map<String, String> promptSnapshots;

    /** Per-task-loop instance id -> the static template key its prompt was snapshotted under. */
    final Map<String, String> templateKeyOf = new HashMap<>();

    /** Judge target step id -> accumulated failure details across re-iterations (FR-4.5). */
    final Map<String, List<String>> accumulatedErrors = new HashMap<>();

    /** Gate step id -> the answer it was closed with, consulted by {@code BranchStep} routing. */
    final Map<String, String> gateAnswers = new HashMap<>();

    /** Step id -> the most recent {@code pending_questions} answers, folded into its next re-run. */
    final Map<String, Map<String, String>> lastAnswers = new HashMap<>();

    /** Target step id -> one-shot replacement prompt text from a judge escalation's {@code
     * edit_prompt} action (FR-11.3); consumed (removed) the next time that step dispatches. */
    final Map<String, String> promptOverrides = new HashMap<>();

    /** Step id -> auto-retries already spent against its {@link dev.forgeide.core.policy.RetryPolicy}
     * (FR-11.2); reset on a PASSED transition or a manual retry, never persisted (a restart's own
     * recovery pass — FR-3.4 — always turns an in-flight attempt into a terminal manual retry). */
    final Map<String, Integer> autoRetryCounts = new HashMap<>();

    /** Step id -> {@code pending_questions} rounds asked in the current phase attempt (FR-10.5);
     * reset at every fresh dispatch (initial run, judge retry, manual retry) but NOT across a
     * question-answer redispatch — that continuity is the entire point of the limit. Same
     * never-persisted rationale as {@link #autoRetryCounts}: a restart resets the budget. */
    final Map<String, Integer> questionRounds = new HashMap<>();

    /** Step id -> human-readable "question -> answer" summary per round so far, for the
     * round-limit escalation dialog's history tab (shared T12 infra). */
    final Map<String, List<String>> questionRoundHistory = new HashMap<>();

    /** Step ids currently {@code WAITING_GATE} because their question-round limit was exhausted
     * (FR-10.5) rather than because they are a real {@code GateStep} or a judge escalation —
     * {@link dev.forgeide.core.engine.PipelineEngine#handleGateAnswered} dispatches on this. */
    final Set<String> questionEscalations = new HashSet<>();

    /** Outward step id -> the branch it pushed (T17), consulted so a later outward step's {@code
     * create_pr} can stack its PR on top of an earlier one instead of always targeting the
     * project's configured base branch. */
    final Map<String, String> outwardBranches = new HashMap<>();

    /** The {@code AgentStep} whose pre-phase {@link HarnessGuardPort#checkDrift} tripped, while
     * the run sits {@code STOPPED(harness-drift)} (SR-8, T18) — {@code null} otherwise. Only ever
     * one at a time: a harness-drift stop halts the whole run, so no other step can be dispatching
     * concurrently. Set right before {@code PipelineEngine#haltOnHarnessDrift}, consumed by
     * {@code PipelineEngine#handleHarnessDriftResolved} to re-dispatch the same step once resolved. */
    String harnessDriftStepId;

    RunContext(PipelineRun run, ProjectDefinition project, String pipelineId, VariableResolver resolver,
               Map<String, StepDefinition> stepDefs, Map<String, String> promptSnapshots) {
        this.run = run;
        this.project = project;
        this.projectRoot = project.repositoryPath();
        this.pipelineId = pipelineId;
        this.resolver = resolver;
        this.stepDefs = stepDefs;
        this.promptSnapshots = promptSnapshots;
    }
}
