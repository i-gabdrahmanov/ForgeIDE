package dev.forgeide.core.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What is stored in {@code <project>/.forgeide/pipeline.yaml} and rendered on the canvas.
 */
public record PipelineDefinition(String id, int version, List<StepDefinition> steps) {

    public PipelineDefinition {
        Objects.requireNonNull(id, "id");
        steps = List.copyOf(steps);
        Set<String> seen = new HashSet<>();
        for (StepDefinition step : steps) {
            if (!seen.add(step.id())) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
        }
    }

    public StepDefinition step(String stepId) {
        return steps.stream()
                .filter(s -> s.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("unknown step: " + stepId));
    }
}
