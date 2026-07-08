package dev.forgeide.core.vars;

/**
 * Thrown when a template is rendered against a resolver that has no value for one of its
 * {@code ${...}} references. Signals a run-time wiring gap, not a static pipeline error.
 */
public final class UnresolvedVariableException extends RuntimeException {

    private final transient VariableReference reference;

    public UnresolvedVariableException(VariableReference reference) {
        super("unresolved variable: " + reference.raw());
        this.reference = reference;
    }

    public VariableReference reference() {
        return reference;
    }
}
