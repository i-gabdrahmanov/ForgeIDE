package dev.forgeide.core.vars;

import java.util.Objects;
import java.util.Set;

/**
 * A single {@code ${scope.key}} occurrence parsed out of a pipeline field (SD §5).
 * References are located when the pipeline is loaded and rendered to concrete values
 * only at run time by a {@link VariableResolver}.
 *
 * @param raw the exact token as it appeared, e.g. {@code ${feature.slug}}
 */
public record VariableReference(String scope, String key, String raw) {

    /** The namespaces a pipeline may reference. */
    public static final Set<String> SCOPES = Set.of("project", "feature", "params");

    public VariableReference {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(raw, "raw");
    }

    public boolean hasKnownScope() {
        return SCOPES.contains(scope);
    }
}
