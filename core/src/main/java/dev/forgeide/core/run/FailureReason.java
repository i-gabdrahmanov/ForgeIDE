package dev.forgeide.core.run;

/**
 * Typed causes of a {@code FAILED} step (SDD FR-11 taxonomy table).
 */
public enum FailureReason {
    /** EOF without a result event, or an unparsable final JSON. */
    STREAM,
    /** {@code expects} artifacts missing, empty, or unparsable (FR-4.3). */
    ARTIFACTS,
    /** Deterministic judge recheck failed after exhausting iterations. */
    JUDGE,
    /** Write outside {@code allowed_write} mask (SR-6). */
    SCOPE,
    /** Manifest projection tamper-hash mismatch (SR-2). */
    TAMPERED,
    /** Token/wall-clock/output-size budget exceeded. */
    BUDGET,
    /** IDE was killed or the run was cancelled mid-phase. */
    INTERRUPTED,
    /** Non-zero exit code from a script step. */
    SCRIPT
}
