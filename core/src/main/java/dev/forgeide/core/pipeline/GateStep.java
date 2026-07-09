package dev.forgeide.core.pipeline;

import dev.forgeide.core.project.RiskLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * @param risk risk class of the action this gate protects (SDD FR-5.3): {@code R2} forces the
 *             "I looked at the diff" checkbox in the UI before the gate can be confirmed.
 */
public record GateStep(
        String id,
        List<String> dependsOn,
        String question,
        List<String> options,
        List<Path> showArtifacts,
        RiskLevel risk
) implements StepDefinition {

    /** Convenience for call sites that don't care about risk classification (defaults to R1). */
    public GateStep(String id, List<String> dependsOn, String question, List<String> options,
                     List<Path> showArtifacts) {
        this(id, dependsOn, question, options, showArtifacts, RiskLevel.R1);
    }

    public GateStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(risk, "risk");
        dependsOn = List.copyOf(dependsOn);
        options = List.copyOf(options);
        showArtifacts = List.copyOf(showArtifacts);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("gate step requires at least one option");
        }
    }
}
