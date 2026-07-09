package dev.forgeide.runtime.state;

import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestStatusTest {

    @Test
    void mapsEveryIdeStatusToAKnownManifestStatus() {
        assertThat(ManifestStatus.of(StepStatus.PASSED)).isEqualTo("completed");
        assertThat(ManifestStatus.of(StepStatus.RUNNING)).isEqualTo("in_progress");
        assertThat(ManifestStatus.of(StepStatus.FAILED)).isEqualTo("failed");
        assertThat(ManifestStatus.of(StepStatus.SKIPPED)).isEqualTo("skipped");
        assertThat(ManifestStatus.of(StepStatus.PENDING)).isEqualTo("pending");
        assertThat(ManifestStatus.of(StepStatus.READY)).isEqualTo("pending");
        assertThat(ManifestStatus.of(StepStatus.WAITING_GATE)).isEqualTo("pending");
        assertThat(ManifestStatus.of(StepStatus.WAITING_INPUT)).isEqualTo("pending");
    }
}
