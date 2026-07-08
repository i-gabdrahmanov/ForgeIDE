package dev.forgeide.core.engine;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.vars.VariableResolver;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    RunContext(PipelineRun run, ProjectDefinition project, VariableResolver resolver,
               Map<String, StepDefinition> stepDefs, Map<String, String> promptSnapshots) {
        this.run = run;
        this.project = project;
        this.projectRoot = project.repositoryPath();
        this.resolver = resolver;
        this.stepDefs = stepDefs;
        this.promptSnapshots = promptSnapshots;
    }
}
