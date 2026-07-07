package dev.forgeide.core.run;

/**
 * Run-level status. {@code PAUSED} and {@code STOPPED} always carry a {@link RunHaltReason}.
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    CANCELLED,
    PAUSED,
    STOPPED
}
