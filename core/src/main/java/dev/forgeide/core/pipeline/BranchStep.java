package dev.forgeide.core.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes to a target step id keyed by the answer of the gate it depends on.
 */
public record BranchStep(
        String id,
        List<String> dependsOn,
        Map<String, String> routes
) implements StepDefinition {

    public BranchStep {
        Objects.requireNonNull(id, "id");
        dependsOn = List.copyOf(dependsOn);
        routes = Map.copyOf(routes);
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("branch step requires at least one route");
        }
    }
}
