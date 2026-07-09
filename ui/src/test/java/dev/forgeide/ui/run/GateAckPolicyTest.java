package dev.forgeide.ui.run;

import dev.forgeide.core.project.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GateAckPolicyTest {

    @Test
    void r2RequiresTheCheckbox() {
        assertThat(GateAckPolicy.requiresDiffAck(RiskLevel.R2)).isTrue();
        assertThat(GateAckPolicy.requiresDiffAck(RiskLevel.R1)).isFalse();
        assertThat(GateAckPolicy.requiresDiffAck(RiskLevel.R0)).isFalse();
    }

    @Test
    void r2CannotConfirmUntilChecked() {
        assertThat(GateAckPolicy.canConfirm(RiskLevel.R2, false)).isFalse();
        assertThat(GateAckPolicy.canConfirm(RiskLevel.R2, true)).isTrue();
    }

    @Test
    void lowerRiskNeverBlocksConfirmation() {
        assertThat(GateAckPolicy.canConfirm(RiskLevel.R0, false)).isTrue();
        assertThat(GateAckPolicy.canConfirm(RiskLevel.R1, false)).isTrue();
    }
}
