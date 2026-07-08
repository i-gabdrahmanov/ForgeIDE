package dev.forgeide.ui.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSearchTest {

    @Test
    void blankOrNullQueryMatchesEverything() {
        assertThat(LogSearch.matches("anything", null)).isTrue();
        assertThat(LogSearch.matches("anything", "")).isTrue();
        assertThat(LogSearch.matches("anything", "   ")).isTrue();
    }

    @Test
    void matchIsCaseInsensitiveSubstring() {
        assertThat(LogSearch.matches("Tool use: Read", "read")).isTrue();
        assertThat(LogSearch.matches("Tool use: Read", "TOOL")).isTrue();
        assertThat(LogSearch.matches("Tool use: Read", "write")).isFalse();
    }

    @Test
    void nullLineNeverMatchesANonBlankQuery() {
        assertThat(LogSearch.matches(null, "x")).isFalse();
    }
}
