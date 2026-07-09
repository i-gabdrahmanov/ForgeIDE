package dev.forgeide.ui.canvas;

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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Full read-only field breakdown for the tile detail panel (FR-2.4: "промпт/скрипт/конфиг").
 * Pure — no JavaFX — so the per-type field set is unit-testable without a display.
 */
public final class StepDetailFields {

    public record Field(String label, String value) {
    }

    private StepDetailFields() {
    }

    public static List<Field> of(StepDefinition step) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("id", step.id()));
        fields.add(new Field("type", StepTileStyles.typeLabel(step)));
        fields.add(new Field("depends_on", joinOrNone(step.dependsOn())));

        switch (step) {
            case AgentStep a -> {
                fields.add(new Field("runtime", a.runtimeRef()));
                fields.add(new Field("prompt", a.promptTemplate().toString()));
                fields.add(new Field("expects", joinPaths(a.expectedArtifacts())));
                fields.add(new Field("allowed_write", joinOrNone(a.allowedWrite())));
                fields.add(new Field("env_scope", joinOrNone(a.envScope())));
                fields.add(new Field("budget", a.budget().tokens() + " tokens, " + a.budget().wallClock()
                        + ", " + a.budget().outputMb() + " MB output"));
                fields.add(new Field("retry", "stream=" + a.retry().stream() + " script=" + a.retry().script()));
            }
            case ScriptStep s -> {
                fields.add(new Field("command", String.join(" ", s.command())));
                fields.add(new Field("timeout", s.timeout().toString()));
            }
            case JudgeStep j -> {
                fields.add(new Field("target", j.targetStepId()));
                j.deterministicCheck().ifPresent(c -> fields.add(new Field("check",
                        String.join(" ", c.command()) + "  (timeout " + c.timeout() + ")")));
                j.llmJudge().ifPresent(l -> fields.add(new Field("llm",
                        l.runtimeRef() + " · " + l.promptTemplate())));
                fields.add(new Field("fail_policy", "max_iterations=" + j.failPolicy().maxIterations()));
            }
            case GateStep g -> {
                fields.add(new Field("question", g.question()));
                fields.add(new Field("options", joinOrNone(g.options())));
                fields.add(new Field("show", joinPaths(g.showArtifacts())));
                fields.add(new Field("risk", g.risk().name()));
            }
            case BranchStep b -> fields.add(new Field("routes", b.routes().entrySet().stream()
                    .map(e -> e.getKey() + " → " + e.getValue())
                    .collect(Collectors.joining(", "))));
            case PerTaskLoop l -> {
                fields.add(new Field("task_plan", l.taskPlanJson().toString()));
                fields.add(new Field("template", l.template().stream()
                        .map(StepDefinition::id)
                        .collect(Collectors.joining(", "))));
            }
            case OutwardStep o -> {
                fields.add(new Field("actions", o.actions().stream()
                        .map(Enum::name)
                        .collect(Collectors.joining(", "))));
                fields.add(new Field("env_scope", joinOrNone(o.envScope())));
            }
        }
        return fields;
    }

    private static String joinOrNone(List<String> values) {
        return values.isEmpty() ? "(none)" : String.join(", ", values);
    }

    private static String joinPaths(List<Path> paths) {
        return paths.isEmpty() ? "(none)" : paths.stream().map(Path::toString).collect(Collectors.joining(", "));
    }
}
