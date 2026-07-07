package dev.forgeide.core.policy;

/**
 * Judge fail-loop limit before escalation to a human (SDD FR-4.5, FR-11.3).
 */
public record FailPolicy(int maxIterations) {

    public static final FailPolicy DEFAULT = new FailPolicy(3);

    public FailPolicy {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1");
        }
    }
}
