package dev.forgeide.core.pipeline.library;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.edit.StepIds;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FR-2.9's "вставка из библиотеки: копирование промпта/конфига, перегенерация id, перевязка
 * путей на структуру целевого проекта" — pure, no I/O: the caller writes {@link Result#files()}
 * into the target project and applies {@link Result#steps()} to a {@link
 * dev.forgeide.core.pipeline.edit.PipelineDocument} itself, same split every other T22 edit uses.
 *
 * <p>Every step gets a fresh id (collision-checked against the caller's existing ids, same {@code
 * StepIds} generator the palette uses); {@code depends_on}/judge {@code target}/branch {@code
 * routes} pointing at another step in the same saved set follow it to the new id, anything
 * pointing outside the set is dropped — an "independent copy", same philosophy as {@code
 * PipelineEdits#duplicateStep}, since the target pipeline has no way to know what that external
 * id used to mean. Prompt files move to {@code prompts/<new-id>.md}; a script file embedded in a
 * {@code command} token moves to {@code scripts/<new-id>/<original-filename>} — both fresh,
 * collision-free locations rather than trying to guess the target project's own layout.
 */
public final class LibraryTileInsertion {

    private LibraryTileInsertion() {
    }

    public record Result(List<StepDefinition> steps, Map<Path, String> files) {
    }

    public static Result insert(LibraryTile tile, Set<String> existingStepIds) {
        Map<String, String> idMap = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>(existingStepIds);
        for (StepDefinition step : tile.steps()) {
            String newId = StepIds.next(idPrefix(step), taken);
            idMap.put(step.id(), newId);
            taken.add(newId);
        }

        Map<Path, String> outFiles = new LinkedHashMap<>();
        List<StepDefinition> steps = new ArrayList<>();
        for (StepDefinition step : tile.steps()) {
            steps.add(rewire(step, idMap.get(step.id()), idMap, tile.files(), outFiles));
        }
        return new Result(steps, outFiles);
    }

    private static StepDefinition rewire(StepDefinition step, String newId, Map<String, String> idMap,
                                          Map<String, String> sourceFiles, Map<Path, String> outFiles) {
        List<String> deps = remap(step.dependsOn(), idMap);
        return switch (step) {
            case AgentStep a -> new AgentStep(newId, deps, a.runtimeRef(),
                    relocatePrompt(newId, a.promptTemplate(), sourceFiles, outFiles),
                    a.expectedArtifacts(), a.allowedWrite(), a.envScope(), a.retry(), a.budget());
            case ScriptStep s -> new ScriptStep(newId, deps,
                    relocateScript(newId, s.command(), sourceFiles, outFiles), s.timeout(), s.retry());
            case JudgeStep j -> {
                String targetId = idMap.getOrDefault(j.targetStepId(), j.targetStepId());
                Optional<AgentStep> llm = j.llmJudge().map(l -> new AgentStep(newId + ".llm", List.of(), l.runtimeRef(),
                        relocatePrompt(newId + ".llm", l.promptTemplate(), sourceFiles, outFiles),
                        l.expectedArtifacts(), l.allowedWrite(), l.envScope(), l.retry(), l.budget()));
                Optional<ScriptStep> check = j.deterministicCheck().map(c -> new ScriptStep(newId + ".check", List.of(),
                        relocateScript(newId + ".check", c.command(), sourceFiles, outFiles), c.timeout(), c.retry()));
                yield new JudgeStep(newId, deps, targetId, llm, check, j.failPolicy());
            }
            case GateStep g -> new GateStep(newId, deps, g.question(), g.options(), g.showArtifacts(), g.risk());
            case BranchStep b -> new BranchStep(newId, deps, remapRoutes(b.routes(), idMap));
            case PerTaskLoop l -> new PerTaskLoop(newId, deps, l.taskPlanJson(), l.template());
            case OutwardStep o -> new OutwardStep(newId, deps, o.actions(), o.envScope(), o.retry());
        };
    }

    private static List<String> remap(List<String> ids, Map<String, String> idMap) {
        List<String> result = new ArrayList<>();
        for (String id : ids) {
            String mapped = idMap.get(id);
            if (mapped != null) {
                result.add(mapped);
            }
            // else: dependency outside the saved subgraph — dropped, not healed (same doctrine
            // PipelineEdits#removeStep documents for a dangling depends_on).
        }
        return result;
    }

    private static Map<String, String> remapRoutes(Map<String, String> routes, Map<String, String> idMap) {
        Map<String, String> result = new LinkedHashMap<>();
        routes.forEach((answer, target) -> result.put(answer, idMap.getOrDefault(target, target)));
        return result;
    }

    private static Path relocatePrompt(String stepId, Path original, Map<String, String> sourceFiles,
                                        Map<Path, String> outFiles) {
        if (original.toString().isBlank()) {
            return original;
        }
        Path newPath = Path.of("prompts", stepId + ".md");
        String content = sourceFiles.get(original.toString());
        if (content != null) {
            outFiles.put(newPath, content);
        }
        return newPath;
    }

    private static List<String> relocateScript(String stepId, List<String> command, Map<String, String> sourceFiles,
                                                 Map<Path, String> outFiles) {
        List<String> result = new ArrayList<>(command);
        for (int i = 0; i < result.size(); i++) {
            String token = result.get(i);
            String content = sourceFiles.get(token);
            if (content == null) {
                continue;
            }
            String fileName = Path.of(token).getFileName().toString();
            Path newPath = Path.of("scripts", stepId, fileName);
            outFiles.put(newPath, content);
            result.set(i, newPath.toString());
        }
        return result;
    }

    private static String idPrefix(StepDefinition step) {
        return switch (step) {
            case AgentStep ignored -> "agent";
            case ScriptStep ignored -> "script";
            case JudgeStep ignored -> "judge";
            case GateStep ignored -> "gate";
            case BranchStep ignored -> "branch";
            case PerTaskLoop ignored -> "per_task_loop";
            case OutwardStep ignored -> "outward";
        };
    }
}
