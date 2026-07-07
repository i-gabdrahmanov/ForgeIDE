package dev.forgeide.core.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepStatusTest {

    @Test
    void matchesSdStateMachineExactly() {
        assertThat(StepStatus.values()).containsExactly(
                StepStatus.PENDING,
                StepStatus.READY,
                StepStatus.RUNNING,
                StepStatus.PASSED,
                StepStatus.FAILED,
                StepStatus.WAITING_GATE,
                StepStatus.WAITING_INPUT,
                StepStatus.SKIPPED
        );
    }
}
