package dev.forgeide.core.project;

/**
 * {@code auto_max_risk} ceiling compatible with the Forge risk-policy (SDD FR-1.3): the
 * highest risk class a step may execute without an explicit human gate. {@code R0} is the
 * strictest (almost everything gated), {@code R2} the most permissive.
 */
public enum RiskLevel {
    R0,
    R1,
    R2
}
