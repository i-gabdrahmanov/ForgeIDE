package dev.forgeide.core.pipeline;

import dev.forgeide.core.policy.RetryPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ScriptStep(
        String id,
        List<String> dependsOn,
        List<String> command,
        Duration timeout,
        RetryPolicy retry
) implements StepDefinition {

    /** Convenience for the common case of no {@code retry:} override (keeps older call sites terse). */
    public ScriptStep(String id, List<String> dependsOn, List<String> command, Duration timeout) {
        this(id, dependsOn, command, timeout, RetryPolicy.DEFAULT);
    }

    public ScriptStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retry, "retry");
        dependsOn = List.copyOf(dependsOn);
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
    }
}
