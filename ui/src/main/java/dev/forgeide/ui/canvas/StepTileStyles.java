package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;

import java.util.stream.Collectors;

/**
 * Per-type presentation for a tile (SD §7: "стилизация CSS по типу шага"; FR-2.4: the tile
 * shows prompt/script/config). Pure string mapping — no JavaFX — so it is unit-testable and
 * reusable by both the tile view and its CSS.
 */
public final class StepTileStyles {

    private StepTileStyles() {
    }

    /** CSS class selector for {@code canvas.css}, e.g. {@code tile-agent}. */
    public static String cssClass(StepDefinition step) {
        return "tile-" + typeKey(step);
    }

    /** Short, capitalised type name shown on the tile header. */
    public static String typeLabel(StepDefinition step) {
        return switch (step) {
            case AgentStep ignored -> "Agent";
            case ScriptStep ignored -> "Script";
            case JudgeStep ignored -> "Judge";
            case GateStep ignored -> "Gate";
            case BranchStep ignored -> "Branch";
            case PerTaskLoop ignored -> "Per-task loop";
            case OutwardStep ignored -> "Outward";
        };
    }

    /** One-line read-only preview of the step's prompt/script/config (FR-2.4). */
    public static String summary(StepDefinition step) {
        return switch (step) {
            case AgentStep a -> a.runtimeRef() + " · " + a.promptTemplate();
            case ScriptStep s -> String.join(" ", s.command());
            case JudgeStep j -> "target: " + j.targetStepId()
                    + (j.llmJudge().isPresent() ? " · llm" : "")
                    + (j.deterministicCheck().isPresent() ? " · check" : "");
            case GateStep g -> g.question();
            case BranchStep b -> b.routes().entrySet().stream()
                    .map(e -> e.getKey() + " → " + e.getValue())
                    .collect(Collectors.joining(", "));
            case PerTaskLoop l -> l.taskPlanJson() + " · " + l.template().size() + " step template";
            case OutwardStep o -> o.actions().stream().map(Enum::name).collect(Collectors.joining(", "));
        };
    }

    private static String typeKey(StepDefinition step) {
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
