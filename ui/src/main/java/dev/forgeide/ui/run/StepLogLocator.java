package dev.forgeide.ui.run;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.run.RunLogLayout;

import java.nio.file.Path;

/**
 * Maps a step + iteration to whatever durable log directory (if any) exists to tail (SDD
 * FR-7.7). Only agent phases — and the LLM half of a judge — write a durable per-iteration log
 * dir (see {@code AbstractAgentRuntime}); script steps and deterministic-only judges have their
 * stdio captured to a throwaway temp dir by {@code ScriptExecutor} and discarded, so there is
 * nothing to tail for them. Pure — no JavaFX — so {@link StepLogView} can stay untested while
 * this mapping is unit-tested on its own.
 */
public final class StepLogLocator {

    private StepLogLocator() {
    }

    public sealed interface StepLogLocation {
        record Directory(Path dir) implements StepLogLocation {
        }

        record NoOutput(String reason) implements StepLogLocation {
        }
    }

    public static StepLogLocation locate(Path projectRoot, String featureSlug, StepDefinition def, int iteration) {
        return switch (def) {
            case AgentStep a -> new StepLogLocation.Directory(
                    RunLogLayout.stepLogDir(projectRoot, featureSlug, a.id(), iteration));
            case JudgeStep j when j.llmJudge().isPresent() -> new StepLogLocation.Directory(
                    RunLogLayout.stepLogDir(projectRoot, featureSlug, j.id(), iteration).resolve("llm"));
            default -> new StepLogLocation.NoOutput("no output captured for this step type");
        };
    }
}
