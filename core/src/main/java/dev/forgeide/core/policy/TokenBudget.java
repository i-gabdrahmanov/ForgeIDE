package dev.forgeide.core.policy;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-phase resource limits (SDD §5.1). Exceeding any of these kills the process
 * tree and fails the step {@code FAILED(budget)}.
 */
public record TokenBudget(long tokens, Duration wallClock, long outputMb) {

    /** Applied wherever a caller has no stronger opinion (SDD §5.1) — parser default, canvas
     * constructor default for a freshly dropped agent tile (T22). */
    public static final TokenBudget DEFAULT = new TokenBudget(2_000_000, Duration.ofMinutes(30), 512);

    public TokenBudget {
        Objects.requireNonNull(wallClock, "wallClock");
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be > 0");
        }
        if (outputMb <= 0) {
            throw new IllegalArgumentException("outputMb must be > 0");
        }
        if (wallClock.isNegative() || wallClock.isZero()) {
            throw new IllegalArgumentException("wallClock must be > 0");
        }
    }
}
