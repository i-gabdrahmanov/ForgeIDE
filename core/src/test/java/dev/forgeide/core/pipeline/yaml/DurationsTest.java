package dev.forgeide.core.pipeline.yaml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DurationsTest {

    @Test
    void parsesCompactUnits() {
        assertThat(Durations.parse("40m")).isEqualTo(Duration.ofMinutes(40));
        assertThat(Durations.parse("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(Durations.parse("500ms")).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void parsesIso8601Fallback() {
        assertThat(Durations.parse("PT40M")).isEqualTo(Duration.ofMinutes(40));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40m", "2h", "1d", "90s", "250ms", "15m"})
    void formatRoundTrips(String literal) {
        Duration parsed = Durations.parse(literal);
        assertThat(Durations.parse(Durations.format(parsed))).isEqualTo(parsed);
    }
}
