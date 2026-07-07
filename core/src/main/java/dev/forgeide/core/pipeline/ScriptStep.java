package dev.forgeide.core.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ScriptStep(
        String id,
        List<String> dependsOn,
        List<String> command,
        Duration timeout
) implements StepDefinition {

    public ScriptStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timeout, "timeout");
        dependsOn = List.copyOf(dependsOn);
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
    }
}
