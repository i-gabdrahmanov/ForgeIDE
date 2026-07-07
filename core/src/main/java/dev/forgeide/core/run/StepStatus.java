package dev.forgeide.core.run;

/**
 * Step state machine (SD §3):
 * {@code PENDING -> READY -> RUNNING -> PASSED | FAILED | WAITING_GATE | WAITING_INPUT | SKIPPED}.
 * Legal transitions are enforced by the engine (T06); this enum only names the states.
 */
public enum StepStatus {
    PENDING,
    READY,
    RUNNING,
    PASSED,
    FAILED,
    WAITING_GATE,
    WAITING_INPUT,
    SKIPPED
}
