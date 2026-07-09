package dev.forgeide.ui.run;

import dev.forgeide.core.project.RiskLevel;

/**
 * Pure gating for the FR-5.3 "I looked at the diff" checkbox: an {@code R2} gate cannot be
 * confirmed until it is checked. No JavaFX — unit-testable without a display.
 */
public final class GateAckPolicy {

    private GateAckPolicy() {
    }

    public static boolean requiresDiffAck(RiskLevel risk) {
        return risk == RiskLevel.R2;
    }

    public static boolean canConfirm(RiskLevel risk, boolean diffAckChecked) {
        return !requiresDiffAck(risk) || diffAckChecked;
    }
}
