package dev.forgeide.core.pipeline;

import dev.forgeide.core.policy.RetryPolicy;

import java.util.List;
import java.util.Objects;

/**
 * Actions with external effect (git push, PR, Jira write) executed by the engine
 * after the gate it depends on, never by an agent phase (SDD §5.1, SR-4).
 */
public record OutwardStep(
        String id,
        List<String> dependsOn,
        List<OutwardAction> actions,
        List<String> envScope,
        RetryPolicy retry
) implements StepDefinition {

    /** Convenience for the common case of no {@code retry:} override (keeps older call sites
     * terse, same pattern as {@code ScriptStep}). */
    public OutwardStep(String id, List<String> dependsOn, List<OutwardAction> actions, List<String> envScope) {
        this(id, dependsOn, actions, envScope, RetryPolicy.DEFAULT);
    }

    public OutwardStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(retry, "retry");
        dependsOn = List.copyOf(dependsOn);
        actions = List.copyOf(actions);
        envScope = List.copyOf(envScope);
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("outward step requires at least one action");
        }
    }
}
