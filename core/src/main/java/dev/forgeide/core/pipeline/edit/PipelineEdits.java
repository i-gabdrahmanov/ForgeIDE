package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * The commands a T22 constructor UI action turns into (FR-2.5: drop-to-create, drag-edge,
 * delete, duplicate). Each factory returns a pure {@link PipelineEdit}; nothing here mutates
 * anything — {@link PipelineDocument#apply} is what pushes the resulting state onto the undo
 * stack.
 */
public final class PipelineEdits {

    private PipelineEdits() {
    }

    public static PipelineEdit addStep(StepDefinition step) {
        return current -> withSteps(current, append(current.steps(), step));
    }

    /** Deliberately does not touch any other step's {@code depends_on}/{@code target}/{@code
     * routes} that pointed at {@code stepId} — those become live-validation errors (FR-2.6),
     * which is the point of live validation: a dangling reference is visible, not silently healed. */
    public static PipelineEdit removeStep(String stepId) {
        return current -> withSteps(current, current.steps().stream()
                .filter(s -> !s.id().equals(stepId))
                .toList());
    }

    /** Independent copy: same config and {@code depends_on}, a fresh id, nothing else points at it. */
    public static PipelineEdit duplicateStep(String stepId, String newId) {
        return current -> {
            StepDefinition copy = withId(current.step(stepId), newId);
            return withSteps(current, append(current.steps(), copy));
        };
    }

    /** Generic field-level edit: the inspector's config form builds the replacement step object
     * (any field, any type — expects/allowed_write/env_scope/budget/retry/fail_policy/routes/…)
     * and this swaps it in place, keeping the step's position in the list. */
    public static PipelineEdit replaceStep(String stepId, StepDefinition replacement) {
        return current -> withSteps(current, current.steps().stream()
                .map(s -> s.id().equals(stepId) ? replacement : s)
                .toList());
    }

    /** FR-2.5 "протяжка ребра между плитками = depends_on". A no-op on a self-loop or an
     * already-present edge; anything that would form a longer cycle is still allowed — that is
     * exactly what live validation (FR-2.6) is for. */
    public static PipelineEdit addDependency(String fromId, String toId) {
        return current -> {
            if (fromId.equals(toId)) {
                return current;
            }
            StepDefinition target = current.step(toId);
            if (target.dependsOn().contains(fromId)) {
                return current;
            }
            List<String> dependsOn = new ArrayList<>(target.dependsOn());
            dependsOn.add(fromId);
            return withSteps(current, current.steps().stream()
                    .map(s -> s.id().equals(toId) ? withDependsOn(target, dependsOn) : s)
                    .toList());
        };
    }

    public static PipelineEdit removeDependency(String fromId, String toId) {
        return current -> {
            StepDefinition target = current.step(toId);
            List<String> dependsOn = new ArrayList<>(target.dependsOn());
            dependsOn.remove(fromId);
            return withSteps(current, current.steps().stream()
                    .map(s -> s.id().equals(toId) ? withDependsOn(target, dependsOn) : s)
                    .toList());
        };
    }

    /** FR-2.7: the YAML tab parses its edited text into a fresh model and applies it as one
     * undo step, so undo/redo stays in sync across both views of the same document. */
    public static PipelineEdit replacePipeline(PipelineDefinition replacement) {
        return current -> replacement;
    }

    private static PipelineDefinition withSteps(PipelineDefinition current, List<StepDefinition> steps) {
        return new PipelineDefinition(current.id(), current.version(), current.params(), steps);
    }

    private static List<StepDefinition> append(List<StepDefinition> steps, StepDefinition step) {
        List<StepDefinition> copy = new ArrayList<>(steps);
        copy.add(step);
        return copy;
    }

    // ---- per-type field surgery (records have no setters) ---------------------------------

    private static StepDefinition withId(StepDefinition step, String newId) {
        return switch (step) {
            case AgentStep a -> new AgentStep(newId, a.dependsOn(), a.runtimeRef(), a.promptTemplate(),
                    a.expectedArtifacts(), a.allowedWrite(), a.envScope(), a.retry(), a.budget());
            case ScriptStep s -> new ScriptStep(newId, s.dependsOn(), s.command(), s.timeout(), s.retry());
            case JudgeStep j -> new JudgeStep(newId, j.dependsOn(), j.targetStepId(),
                    j.llmJudge().map(llm -> (AgentStep) withId(llm, newId + ".llm")),
                    j.deterministicCheck().map(c -> (ScriptStep) withId(c, newId + ".check")),
                    j.failPolicy());
            case GateStep g -> new GateStep(newId, g.dependsOn(), g.question(), g.options(), g.showArtifacts(), g.risk());
            case BranchStep b -> new BranchStep(newId, b.dependsOn(), b.routes());
            case PerTaskLoop l -> new PerTaskLoop(newId, l.dependsOn(), l.taskPlanJson(), l.template());
            case OutwardStep o -> new OutwardStep(newId, o.dependsOn(), o.actions(), o.envScope(), o.retry());
        };
    }

    private static StepDefinition withDependsOn(StepDefinition step, List<String> dependsOn) {
        return switch (step) {
            case AgentStep a -> new AgentStep(a.id(), dependsOn, a.runtimeRef(), a.promptTemplate(),
                    a.expectedArtifacts(), a.allowedWrite(), a.envScope(), a.retry(), a.budget());
            case ScriptStep s -> new ScriptStep(s.id(), dependsOn, s.command(), s.timeout(), s.retry());
            case JudgeStep j -> new JudgeStep(j.id(), dependsOn, j.targetStepId(), j.llmJudge(),
                    j.deterministicCheck(), j.failPolicy());
            case GateStep g -> new GateStep(g.id(), dependsOn, g.question(), g.options(), g.showArtifacts(), g.risk());
            case BranchStep b -> new BranchStep(b.id(), dependsOn, b.routes());
            case PerTaskLoop l -> new PerTaskLoop(l.id(), dependsOn, l.taskPlanJson(), l.template());
            case OutwardStep o -> new OutwardStep(o.id(), dependsOn, o.actions(), o.envScope(), o.retry());
        };
    }
}
