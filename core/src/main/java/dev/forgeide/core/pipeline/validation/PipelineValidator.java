package dev.forgeide.core.pipeline.validation;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.PipelineParam;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.vars.VariableReference;
import dev.forgeide.core.vars.Variables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Static checks over a constructed {@link PipelineDefinition} (SDD FR-2.3): the graph is
 * acyclic, every step is reachable, every referenced id/param exists, each {@code outward}
 * step is preceded by a judge, and every {@code ${...}} reference names a known scope. The
 * same instance drives load-time validation and the canvas live-validation (FR-2.6), so it
 * returns errors rather than throwing.
 *
 * <p>Uniqueness of ids and per-step field invariants are enforced by the domain records and
 * the loader; this validator assumes the model already constructed.
 */
public final class PipelineValidator {

    /**
     * Optional filesystem context. When a project root is present, static (variable-free)
     * prompt paths are checked for existence; without it, only in-model references are checked.
     */
    public record Options(Optional<Path> projectRoot) {
        public static Options none() {
            return new Options(Optional.empty());
        }

        public static Options withRoot(Path projectRoot) {
            return new Options(Optional.of(projectRoot));
        }
    }

    public List<PipelineError> validate(PipelineDefinition definition) {
        return validate(definition, Options.none());
    }

    public List<PipelineError> validate(PipelineDefinition definition, Options options) {
        List<PipelineError> errors = new ArrayList<>();
        Map<String, StepDefinition> byId = new HashMap<>();
        for (StepDefinition step : definition.steps()) {
            byId.put(step.id(), step);
        }

        checkReferences(definition, byId, errors);
        checkAcyclicAndReachable(definition, byId, errors);
        checkJudgeBeforeOutward(definition, byId, errors);
        checkVariables(definition, errors);
        checkPromptFiles(definition, options, errors);
        return errors;
    }

    /** depends_on / target / routes must all name existing steps. */
    private void checkReferences(PipelineDefinition def, Map<String, StepDefinition> byId, List<PipelineError> errors) {
        for (StepDefinition step : def.steps()) {
            for (String dep : step.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    errors.add(PipelineError.atStep(step.id(), "depends_on", "unknown step '" + dep + "'"));
                }
            }
            if (step instanceof JudgeStep judge && !byId.containsKey(judge.targetStepId())) {
                errors.add(PipelineError.atStep(step.id(), "target", "unknown target step '" + judge.targetStepId() + "'"));
            }
            if (step instanceof BranchStep branch) {
                branch.routes().forEach((answer, target) -> {
                    if (!byId.containsKey(target)) {
                        errors.add(PipelineError.atStep(step.id(), "routes",
                                "route '" + answer + "' points at unknown step '" + target + "'"));
                    }
                });
            }
        }
    }

    /** Kahn topo-sort over depends_on edges detects cycles; a forward BFS finds orphans. */
    private void checkAcyclicAndReachable(PipelineDefinition def, Map<String, StepDefinition> byId, List<PipelineError> errors) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (StepDefinition step : def.steps()) {
            indegree.putIfAbsent(step.id(), 0);
            for (String dep : step.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    continue; // dangling ref already reported
                }
                indegree.merge(step.id(), 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(step.id());
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                ready.add(id);
            }
        });
        int processed = 0;
        Map<String, Integer> remaining = new HashMap<>(indegree);
        while (!ready.isEmpty()) {
            String id = ready.poll();
            processed++;
            for (String next : dependents.getOrDefault(id, List.of())) {
                if (remaining.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (processed < def.steps().size()) {
            for (StepDefinition step : def.steps()) {
                if (remaining.getOrDefault(step.id(), 0) > 0) {
                    errors.add(PipelineError.atStep(step.id(), "depends_on", "step is part of a dependency cycle"));
                }
            }
            return; // reachability is meaningless while a cycle stands
        }

        Set<String> reachable = reachableFromEntries(def, byId);
        for (StepDefinition step : def.steps()) {
            if (!reachable.contains(step.id())) {
                errors.add(PipelineError.atStep(step.id(), "depends_on", "step is unreachable from any entry step"));
            }
        }
    }

    private Set<String> reachableFromEntries(PipelineDefinition def, Map<String, StepDefinition> byId) {
        Map<String, List<String>> forward = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (StepDefinition step : def.steps()) {
            if (step.dependsOn().isEmpty()) {
                queue.add(step.id());
            }
            for (String dep : step.dependsOn()) {
                if (byId.containsKey(dep)) {
                    forward.computeIfAbsent(dep, k -> new ArrayList<>()).add(step.id());
                }
            }
            if (step instanceof BranchStep branch) {
                for (String target : branch.routes().values()) {
                    if (byId.containsKey(target)) {
                        forward.computeIfAbsent(step.id(), k -> new ArrayList<>()).add(target);
                    }
                }
            }
        }
        Set<String> reachable = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            for (String next : forward.getOrDefault(queue.poll(), List.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    /** Each outward step must have a judge somewhere in its depends_on closure (SR-4, FR-6.1). */
    private void checkJudgeBeforeOutward(PipelineDefinition def, Map<String, StepDefinition> byId, List<PipelineError> errors) {
        for (StepDefinition step : def.steps()) {
            if (!(step instanceof OutwardStep)) {
                continue;
            }
            if (!hasUpstreamJudge(step, byId)) {
                errors.add(PipelineError.atStep(step.id(), "depends_on",
                        "outward step must be preceded by a judge"));
            }
        }
    }

    private boolean hasUpstreamJudge(StepDefinition outward, Map<String, StepDefinition> byId) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(outward.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            StepDefinition step = byId.get(id);
            if (step == null) {
                continue;
            }
            if (step instanceof JudgeStep) {
                return true;
            }
            queue.addAll(step.dependsOn());
        }
        return false;
    }

    /** Every ${scope.key} must name a known scope; params must be declared. */
    private void checkVariables(PipelineDefinition def, List<PipelineError> errors) {
        Set<String> declaredParams = new HashSet<>();
        for (PipelineParam param : def.params()) {
            declaredParams.add(param.name());
        }
        for (StepDefinition step : def.steps()) {
            scanVariables(step, declaredParams, errors);
        }
    }

    private void scanVariables(StepDefinition step, Set<String> declaredParams, List<PipelineError> errors) {
        forEachTemplate(step, (field, text) -> {
            for (VariableReference ref : Variables.references(text)) {
                if (!ref.hasKnownScope()) {
                    errors.add(PipelineError.atStep(step.id(), field,
                            "unknown variable scope '" + ref.scope() + "' in " + ref.raw()));
                } else if (ref.scope().equals("params") && !declaredParams.contains(ref.key())) {
                    errors.add(PipelineError.atStep(step.id(), field,
                            "reference to undeclared param '" + ref.key() + "'"));
                }
            }
        });
    }

    private void checkPromptFiles(PipelineDefinition def, Options options, List<PipelineError> errors) {
        if (options.projectRoot().isEmpty()) {
            return;
        }
        Path root = options.projectRoot().get();
        for (StepDefinition step : def.steps()) {
            if (step instanceof AgentStep agent) {
                checkPromptFile(step.id(), agent, root, errors);
            }
            if (step instanceof JudgeStep judge) {
                judge.llmJudge().ifPresent(llm -> checkPromptFile(step.id(), llm, root, errors));
            }
        }
    }

    private void checkPromptFile(String stepId, AgentStep agent, Path root, List<PipelineError> errors) {
        String prompt = agent.promptTemplate().toString();
        if (Variables.hasReferences(prompt)) {
            return; // unresolved at design time — can only be checked at run time
        }
        if (!Files.isRegularFile(root.resolve(prompt))) {
            errors.add(PipelineError.atStep(stepId, "prompt", "prompt file not found: " + prompt));
        }
    }

    /** Feeds each path-like field of {@code step} (recursing into nested steps) to {@code sink}. */
    private void forEachTemplate(StepDefinition step, TemplateSink sink) {
        switch (step) {
            case AgentStep a -> {
                sink.accept("prompt", a.promptTemplate().toString());
                a.expectedArtifacts().forEach(p -> sink.accept("expects", p.toString()));
                a.allowedWrite().forEach(w -> sink.accept("allowed_write", w));
            }
            case ScriptStep s -> s.command().forEach(arg -> sink.accept("command", arg));
            case GateStep g -> g.showArtifacts().forEach(p -> sink.accept("show", p.toString()));
            case JudgeStep j -> {
                j.deterministicCheck().ifPresent(c -> forEachTemplate(c, sink));
                j.llmJudge().ifPresent(l -> forEachTemplate(l, sink));
            }
            case PerTaskLoop l -> {
                sink.accept("task_plan", l.taskPlanJson().toString());
                l.template().forEach(nested -> forEachTemplate(nested, sink));
            }
            case BranchStep ignored -> {
            }
            case OutwardStep ignored -> {
            }
        }
    }

    @FunctionalInterface
    private interface TemplateSink {
        void accept(String field, String text);
    }
}
