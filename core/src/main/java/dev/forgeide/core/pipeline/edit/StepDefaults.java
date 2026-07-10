package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.project.RiskLevel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a fresh tile dropped from the T22 palette looks like (FR-2.5: "перетаскивание на канвас
 * создаёт шаг"). Every default satisfies its record's own constructor invariants (a {@code
 * GateStep} needs an option, a {@code JudgeStep} needs a check-or-llm, …) but is otherwise
 * deliberately incomplete — a target that names no real step — so the live validator (FR-2.6)
 * immediately badges the tile until the user fills it in, exactly like the GWT in the task's
 * acceptance criteria.
 *
 * <p>T23/FR-2.8 exception: a fresh {@code AgentStep} already points at a real
 * {@code prompts/<id>.md} — this class is pure (no I/O), so the file itself does not exist yet
 * until the caller also seeds it (see {@link AgentPromptScaffold}, wired in by {@code
 * ConstructorCanvasView}'s new-agent hook right after this runs) — until then the live validator
 * correctly badges the tile with "prompt file not found", same as any other incomplete default.
 */
public final class StepDefaults {

    private static final Duration DEFAULT_SCRIPT_TIMEOUT = Duration.ofMinutes(5);

    private StepDefaults() {
    }

    public static StepDefinition create(StepKind kind, String id) {
        return switch (kind) {
            case AGENT -> new AgentStep(id, List.of(), "claude", Path.of("prompts", id + ".md"), List.of(), List.of(),
                    List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
            case SCRIPT -> new ScriptStep(id, List.of(), List.of("echo", "todo"), DEFAULT_SCRIPT_TIMEOUT,
                    RetryPolicy.DEFAULT);
            case JUDGE -> {
                ScriptStep check = new ScriptStep(id + ".check", List.of(), List.of("true"), DEFAULT_SCRIPT_TIMEOUT);
                yield new JudgeStep(id, List.of(), "", Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
            }
            case GATE -> new GateStep(id, List.of(), "", List.of("yes", "no"), List.of(), RiskLevel.R1);
            case BRANCH -> new BranchStep(id, List.of(), Map.of("default", ""));
            case PER_TASK_LOOP -> {
                ScriptStep task = new ScriptStep(id + ".task", List.of(), List.of("echo", "todo"),
                        DEFAULT_SCRIPT_TIMEOUT);
                yield new PerTaskLoop(id, List.of(), Path.of(""), List.of(task));
            }
            case OUTWARD -> new OutwardStep(id, List.of(), List.of(OutwardAction.GIT_PUSH), List.of(),
                    RetryPolicy.DEFAULT);
        };
    }
}
