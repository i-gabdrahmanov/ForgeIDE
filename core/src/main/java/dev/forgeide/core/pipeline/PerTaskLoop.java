package dev.forgeide.core.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Expands {@code template} into one subgraph per entry of {@code taskPlanJson}
 * once the source step it depends on closes (engine responsibility, T06).
 */
public record PerTaskLoop(
        String id,
        List<String> dependsOn,
        Path taskPlanJson,
        List<StepDefinition> template
) implements StepDefinition {

    public PerTaskLoop {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskPlanJson, "taskPlanJson");
        dependsOn = List.copyOf(dependsOn);
        template = List.copyOf(template);
        if (template.isEmpty()) {
            throw new IllegalArgumentException("per_task_loop requires a non-empty template");
        }
    }
}
