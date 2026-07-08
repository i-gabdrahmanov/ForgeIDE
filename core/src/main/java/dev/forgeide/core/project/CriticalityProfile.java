package dev.forgeide.core.project;

/**
 * Project criticality profile (SDD FR-1.3, BT §4.1): drives the {@link RiskLevel} ceiling
 * applied to auto-approved steps. The more critical the project, the stricter the ceiling —
 * {@code high} caps auto-approval at {@link RiskLevel#R0}, {@code low} allows up to {@link RiskLevel#R2}.
 */
public enum CriticalityProfile {
    LOW(RiskLevel.R2),
    MEDIUM(RiskLevel.R1),
    HIGH(RiskLevel.R0);

    private final RiskLevel maxRisk;

    CriticalityProfile(RiskLevel maxRisk) {
        this.maxRisk = maxRisk;
    }

    public RiskLevel maxRisk() {
        return maxRisk;
    }
}
