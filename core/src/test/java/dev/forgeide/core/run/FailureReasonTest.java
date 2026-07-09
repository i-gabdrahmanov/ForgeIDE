package dev.forgeide.core.run;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FailureReasonTest {

    @Test
    void matchesFr11Taxonomy() {
        assertThat(FailureReason.values()).containsExactlyInAnyOrder(
                FailureReason.STREAM,
                FailureReason.ARTIFACTS,
                FailureReason.JUDGE,
                FailureReason.SCOPE,
                FailureReason.TAMPERED,
                FailureReason.BUDGET,
                FailureReason.INTERRUPTED,
                FailureReason.SCRIPT,
                // Not in the FR-11 table itself — FR-10.5/Т-15's pending_questions round-limit
                // escalation, resolved as "as FAIL" the same way a judge exhaustion is.
                FailureReason.QUESTIONS
        );
    }

    @Test
    void onlyScopeAndTamperedBlockAManualRetry() {
        assertThat(FailureReason.SCOPE.blocksManualRetry()).isTrue();
        assertThat(FailureReason.TAMPERED.blocksManualRetry()).isTrue();
        assertThat(Arrays.stream(FailureReason.values()).filter(FailureReason::blocksManualRetry))
                .containsExactlyInAnyOrder(FailureReason.SCOPE, FailureReason.TAMPERED);
    }
}
