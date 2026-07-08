package dev.forgeide.core.vars;

import java.util.Optional;

/**
 * Renders {@code ${project.*}}, {@code ${feature.*}} and {@code ${params.*}} references
 * to concrete values at run time (T03 scope: interface only; the engine wires the values).
 * Pipeline loading parses references but never resolves them — the same definition renders
 * differently per feature/run.
 */
public interface VariableResolver {

    /** The value bound to {@code ref}, or empty if this resolver has none. */
    Optional<String> resolve(VariableReference ref);

    /**
     * Renders all references in {@code template}.
     *
     * @throws UnresolvedVariableException on the first reference this resolver cannot bind
     */
    default String render(String template) {
        return Variables.render(template, this);
    }
}
