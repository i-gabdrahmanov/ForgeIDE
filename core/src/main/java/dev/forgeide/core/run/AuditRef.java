package dev.forgeide.core.run;

/**
 * Lightweight pointer from a {@link StepRun} into the audit hash-chain (T07 owns the
 * actual envelope); lets the UI jump from a step to its audit trail.
 */
public record AuditRef(long seq, String type) {
}
