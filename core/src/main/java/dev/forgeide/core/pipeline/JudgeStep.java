package dev.forgeide.core.pipeline;

import dev.forgeide.core.policy.FailPolicy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Composition of an optional LLM verdict plus a deterministic recheck (SDD FR-6.1).
 * The deterministic check always decides when it is present; it is mandatory for
 * judges that gate an {@link OutwardStep}.
 */
public record JudgeStep(
        String id,
        List<String> dependsOn,
        String targetStepId,
        Optional<AgentStep> llmJudge,
        Optional<ScriptStep> deterministicCheck,
        FailPolicy failPolicy
) implements StepDefinition {

    public JudgeStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetStepId, "targetStepId");
        Objects.requireNonNull(llmJudge, "llmJudge");
        Objects.requireNonNull(deterministicCheck, "deterministicCheck");
        Objects.requireNonNull(failPolicy, "failPolicy");
        dependsOn = List.copyOf(dependsOn);
        if (llmJudge.isEmpty() && deterministicCheck.isEmpty()) {
            throw new IllegalArgumentException("judge step requires at least one of llmJudge/deterministicCheck");
        }
    }
}
