package dev.forgeide.core.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record GateStep(
        String id,
        List<String> dependsOn,
        String question,
        List<String> options,
        List<Path> showArtifacts
) implements StepDefinition {

    public GateStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        dependsOn = List.copyOf(dependsOn);
        options = List.copyOf(options);
        showArtifacts = List.copyOf(showArtifacts);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("gate step requires at least one option");
        }
    }
}
