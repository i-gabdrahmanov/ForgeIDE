package dev.forgeide.core.port;

import java.time.Instant;

/**
 * Deterministic time source so engine/audit tests do not depend on the wall clock.
 */
public interface Clock {
    Instant now();
}
