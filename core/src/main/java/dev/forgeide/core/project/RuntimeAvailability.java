package dev.forgeide.core.project;

import java.util.Objects;

/**
 * Outcome of a {@code --version} probe against a {@link RuntimeBinding} (SDD FR-1.2). {@code
 * detail} carries the version output on success, or a human-readable reason on failure — the
 * UI shows it verbatim, so it must never be blank.
 */
public record RuntimeAvailability(RuntimeStatus status, String detail) {

    public RuntimeAvailability {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    public static RuntimeAvailability unknown() {
        return new RuntimeAvailability(RuntimeStatus.UNKNOWN, "");
    }

    public static RuntimeAvailability available(String versionOutput) {
        return new RuntimeAvailability(RuntimeStatus.AVAILABLE, versionOutput);
    }

    public static RuntimeAvailability unavailable(String reason) {
        return new RuntimeAvailability(RuntimeStatus.UNAVAILABLE, reason);
    }
}
