package dev.forgeide.core.run;

/**
 * Why a run is {@code PAUSED} or {@code STOPPED} (SDD FR-11.4, FR-7.3, SR-8).
 */
public enum RunHaltReason {
    /** Engine actor threw an uncaught exception (FR-11.4). */
    ENGINE_ERROR,
    /** Harness hash-manifest drifted before an agent phase (SR-8). */
    HARNESS_DRIFT,
    /** Audit hash-chain broke on load (SR-3, FR-7.3). */
    AUDIT_CHAIN,
    /** Harness not deployed, or its last preflight did not pass (FR-1.4): "enforcement off". */
    HARNESS_PREFLIGHT
}
