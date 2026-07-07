package dev.forgeide.core.run;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of one judge iteration. The deterministic recheck always decides when a
 * blocking judgement is required (SDD FR-6.1); the LLM verdict is advisory only.
 * Stored in {@link StepRun#verdicts()}, never exposed to the agent (Т-3).
 */
public record JudgeVerdict(
        int iteration,
        Optional<Boolean> llmPassed,
        boolean deterministicPassed,
        String detail
) {

    public JudgeVerdict {
        Objects.requireNonNull(llmPassed, "llmPassed");
        Objects.requireNonNull(detail, "detail");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be >= 1");
        }
    }

    public boolean passed() {
        return deterministicPassed;
    }
}
