package dev.forgeide.core.engine;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure helpers for {@link PerTaskLoop} (T06 scope): locating the prompt files a snapshot
 * must read at run start, and cloning a loop's {@code template} into one namespaced subgraph
 * per task-plan entry. A {@code per_task_loop} nested inside another loop's template gets a
 * collision-free id like any other template step, but is only expanded once it becomes ready
 * in its own right — prompt lookups for agent steps nested two or more loops deep are a known
 * gap the engine does not attempt to close.
 */
final class TemplateExpansion {

    private TemplateExpansion() {
    }

    /**
     * Collects {@code stepId -> promptTemplate path} for every {@link AgentStep} reachable
     * from {@code steps} (top-level agents, judge {@code llm} sub-verdicts, and loop templates).
     * Loop template entries are keyed {@code "<loopId>/<templateStepId>"} so they cannot
     * collide with a top-level id of the same name.
     */
    static void collectPromptPaths(List<StepDefinition> steps, String prefix, Map<String, Path> out) {
        for (StepDefinition step : steps) {
            if (step instanceof AgentStep agent) {
                out.put(prefix + agent.id(), agent.promptTemplate());
            } else if (step instanceof JudgeStep judge) {
                judge.llmJudge().ifPresent(llm -> out.put(prefix + llm.id(), llm.promptTemplate()));
            } else if (step instanceof PerTaskLoop loop) {
                collectPromptPaths(loop.template(), prefix + loop.id() + "/", out);
            }
        }
    }

    /**
     * One namespaced copy of {@code loop.template()} for a single task-plan entry: every
     * step id becomes {@code "<loopId>/<taskId>/<templateStepId>"}, and any {@code depends_on}
     * / judge {@code target} / branch {@code routes} value that names another template step is
     * remapped to the same namespaced id. References outside the template are left untouched.
     */
    static List<StepDefinition> expandForTask(PerTaskLoop loop, String taskId) {
        Set<String> localIds = loop.template().stream().map(StepDefinition::id).collect(Collectors.toSet());
        List<StepDefinition> expanded = new ArrayList<>(loop.template().size());
        for (StepDefinition step : loop.template()) {
            expanded.add(namespace(step, loop.id(), taskId, localIds));
        }
        return expanded;
    }

    private static String instanceId(String loopId, String taskId, String localId) {
        return loopId + "/" + taskId + "/" + localId;
    }

    private static String remapIfLocal(String ref, String loopId, String taskId, Set<String> localIds) {
        return localIds.contains(ref) ? instanceId(loopId, taskId, ref) : ref;
    }

    private static List<String> remapDeps(List<String> deps, String loopId, String taskId, Set<String> localIds) {
        return deps.stream().map(d -> remapIfLocal(d, loopId, taskId, localIds)).toList();
    }

    private static StepDefinition namespace(StepDefinition step, String loopId, String taskId, Set<String> localIds) {
        String newId = instanceId(loopId, taskId, step.id());
        List<String> newDeps = remapDeps(step.dependsOn(), loopId, taskId, localIds);
        return switch (step) {
            case AgentStep a -> new AgentStep(newId, newDeps, a.runtimeRef(), a.promptTemplate(),
                    a.expectedArtifacts(), a.allowedWrite(), a.envScope(), a.retry(), a.budget());
            case ScriptStep s -> new ScriptStep(newId, newDeps, s.command(), s.timeout(), s.retry());
            case JudgeStep j -> new JudgeStep(newId, newDeps,
                    remapIfLocal(j.targetStepId(), loopId, taskId, localIds),
                    j.llmJudge(), j.deterministicCheck(), j.failPolicy());
            case GateStep g -> new GateStep(newId, newDeps, g.question(), g.options(), g.showArtifacts());
            case BranchStep b -> {
                Map<String, String> newRoutes = new LinkedHashMap<>();
                b.routes().forEach((answer, target) ->
                        newRoutes.put(answer, remapIfLocal(target, loopId, taskId, localIds)));
                yield new BranchStep(newId, newDeps, newRoutes);
            }
            case OutwardStep o -> new OutwardStep(newId, newDeps, o.actions(), o.envScope(), o.retry());
            // Id/deps are namespaced like any other step; the nested template itself is
            // expanded later, on its own terms, once this instance becomes ready.
            case PerTaskLoop nested -> new PerTaskLoop(newId, newDeps, nested.taskPlanJson(), nested.template());
        };
    }
}
