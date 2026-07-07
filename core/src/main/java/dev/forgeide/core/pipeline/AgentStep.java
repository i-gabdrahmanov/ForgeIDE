package dev.forgeide.core.pipeline;

import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One headless call to an {@code AgentRuntimePort} (SDD FR-4).
 *
 * @param allowedWrite glob masks checked by scope-diff after the phase (SR-6)
 * @param envScope     env keys injected into the phase; empty means no secrets (SR-5)
 */
public record AgentStep(
        String id,
        List<String> dependsOn,
        String runtimeRef,
        Path promptTemplate,
        List<Path> expectedArtifacts,
        List<String> allowedWrite,
        List<String> envScope,
        RetryPolicy retry,
        TokenBudget budget
) implements StepDefinition {

    public AgentStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runtimeRef, "runtimeRef");
        Objects.requireNonNull(promptTemplate, "promptTemplate");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(budget, "budget");
        dependsOn = List.copyOf(dependsOn);
        expectedArtifacts = List.copyOf(expectedArtifacts);
        allowedWrite = List.copyOf(allowedWrite);
        envScope = List.copyOf(envScope);
    }
}
