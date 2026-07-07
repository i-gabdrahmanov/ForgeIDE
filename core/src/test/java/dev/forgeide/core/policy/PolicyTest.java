package dev.forgeide.core.policy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyTest {

    @Test
    void retryPolicyRejectsNegativeCounts() {
        assertThatThrownBy(() -> new RetryPolicy(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failPolicyRequiresAtLeastOneIteration() {
        assertThatThrownBy(() -> new FailPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenBudgetRejectsNonPositiveValues() {
        assertThatThrownBy(() -> new TokenBudget(0, Duration.ofMinutes(1), 512))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBudget(1000, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBudget(1000, Duration.ZERO, 512))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
