package dev.forgeide.core.policy;

/**
 * Per-step auto-retry counts (SDD FR-11.2). Auto-retry only ever applies to the
 * classes where it is safe: stream drops and script failures.
 */
public record RetryPolicy(int stream, int script) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(1, 0);

    public RetryPolicy {
        if (stream < 0) {
            throw new IllegalArgumentException("stream retry count must be >= 0");
        }
        if (script < 0) {
            throw new IllegalArgumentException("script retry count must be >= 0");
        }
    }
}
